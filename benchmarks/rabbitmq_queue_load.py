"""Run a real, isolated RabbitMQ publish/consume load test without touching business queues.

The benchmark declares an exclusive, auto-delete queue so it cannot retain or consume teacher-source-sync commands.
It measures AMQP publisher confirms, broker queueing, consumer throughput and message end-to-end latency against the
configured local RabbitMQ instance.
"""

from __future__ import annotations

import argparse
import json
import statistics
import threading
import time
import uuid
from dataclasses import dataclass

import pika


@dataclass(frozen=True)
class BenchmarkSettings:
    """Explicit test settings; arguments avoid hidden workload constants."""

    host: str
    port: int
    message_count: int
    payload_bytes: int
    consumer_count: int
    timeout_seconds: float


def percentile(values: list[float], fraction: float) -> float:
    """Return a linearly interpolated percentile for the observed, real message latencies."""
    if not values:
        return 0.0
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def run(settings: BenchmarkSettings) -> dict[str, float | int | str]:
    """Publish and consume real persistent AMQP messages through one temporary queue."""
    credentials = pika.PlainCredentials("guest", "guest")
    parameters = pika.ConnectionParameters(
        host=settings.host,
        port=settings.port,
        credentials=credentials,
        heartbeat=max(1, int(settings.timeout_seconds)),
        blocked_connection_timeout=settings.timeout_seconds,
    )
    exchange = f"math-agent.benchmark.{uuid.uuid4()}"
    routing_key = "load"
    received_at: list[float] = []
    published_at: dict[int, float] = {}
    received_lock = threading.Lock()
    all_received = threading.Event()
    consumer_ready = threading.Event()
    consumer_state: dict[str, object] = {}
    payload = "x" * settings.payload_bytes

    def consume() -> None:
        """Own the AMQP consumer connection so Pika never crosses a channel between threads."""
        consumer_connection = pika.BlockingConnection(parameters)
        consumer_channel = consumer_connection.channel()
        consumer_channel.exchange_declare(exchange=exchange, exchange_type="direct", durable=False)
        result = consumer_channel.queue_declare(queue="", exclusive=True, auto_delete=True)
        queue_name = result.method.queue
        consumer_channel.queue_bind(exchange=exchange, queue=queue_name, routing_key=routing_key)
        consumer_channel.basic_qos(prefetch_count=settings.consumer_count)
        consumer_state.update(connection=consumer_connection, channel=consumer_channel, queue=queue_name)
        consumer_ready.set()

        def on_message(channel: pika.adapters.blocking_connection.BlockingChannel, method, _properties, body: bytes) -> None:
            message = json.loads(body)
            latency_ms = (time.perf_counter() - published_at[message["sequence"]]) * 1_000
            with received_lock:
                received_at.append(latency_ms)
                if len(received_at) == settings.message_count:
                    all_received.set()
            channel.basic_ack(delivery_tag=method.delivery_tag)

        consumer_channel.basic_consume(queue=queue_name, on_message_callback=on_message, auto_ack=False)
        consumer_channel.start_consuming()

    consumer_thread = threading.Thread(target=consume, name="rabbitmq-load-consumer", daemon=True)
    consumer_thread.start()
    if not consumer_ready.wait(timeout=settings.timeout_seconds):
        raise TimeoutError("RabbitMQ consumer queue was not ready")
    publisher_connection = pika.BlockingConnection(parameters)
    publisher_channel = publisher_connection.channel()
    publisher_channel.confirm_delivery()
    started = time.perf_counter()
    try:
        for sequence in range(settings.message_count):
            published_at[sequence] = time.perf_counter()
            publisher_channel.basic_publish(
                exchange=exchange,
                routing_key=routing_key,
                body=json.dumps({"sequence": sequence, "payload": payload}),
                properties=pika.BasicProperties(delivery_mode=pika.DeliveryMode.Persistent),
                mandatory=True,
            )
        if not all_received.wait(timeout=settings.timeout_seconds):
            raise TimeoutError(f"Only {len(received_at)}/{settings.message_count} messages were consumed")
    finally:
        publisher_connection.close()
        # BlockingConnection channels are thread-affine; schedule the shutdown on its own IO thread so the real
        # benchmark cannot hang after it has collected all acknowledgements.
        consumer_connection = consumer_state["connection"]
        consumer_channel = consumer_state["channel"]
        consumer_connection.add_callback_threadsafe(consumer_channel.stop_consuming)
        consumer_thread.join(timeout=settings.timeout_seconds)
        consumer_connection.close()
    elapsed = time.perf_counter() - started
    return {
        "messages": settings.message_count,
        "payloadBytes": settings.payload_bytes,
        "consumers": settings.consumer_count,
        "elapsedSeconds": round(elapsed, 3),
        "throughputMessagesPerSecond": round(settings.message_count / elapsed, 2),
        "latencyMsP50": round(statistics.median(received_at), 3),
        "latencyMsP95": round(percentile(received_at, 0.95), 3),
        "latencyMsP99": round(percentile(received_at, 0.99), 3),
        "queue": str(consumer_state["queue"]),
    }


def main() -> None:
    """Parse explicit inputs and print a machine-readable measurement record."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5672)
    parser.add_argument("--messages", type=int, default=5_000)
    parser.add_argument("--payload-bytes", type=int, default=512)
    parser.add_argument("--consumers", type=int, default=1)
    parser.add_argument("--timeout-seconds", type=float, default=60)
    args = parser.parse_args()
    print(json.dumps(run(BenchmarkSettings(
        args.host, args.port, args.messages, args.payload_bytes, args.consumers, args.timeout_seconds)), ensure_ascii=False))


if __name__ == "__main__":
    main()

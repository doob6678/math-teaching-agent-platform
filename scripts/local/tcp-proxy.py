import argparse
import selectors
import socket
import threading


def pipe(left, right):
    selector = selectors.DefaultSelector()
    selector.register(left, selectors.EVENT_READ, right)
    selector.register(right, selectors.EVENT_READ, left)
    try:
        while True:
            events = selector.select()
            for key, _ in events:
                source = key.fileobj
                target = key.data
                data = source.recv(65536)
                if not data:
                    return
                target.sendall(data)
    finally:
        selector.close()
        for item in (left, right):
            try:
                item.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            item.close()


def handle(client, args):
    try:
        upstream = socket.create_connection((args.target_host, args.target_port), timeout=10)
    except OSError:
        client.close()
        return
    pipe(client, upstream)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-host", default="127.0.0.1")
    parser.add_argument("--listen-port", type=int, required=True)
    parser.add_argument("--target-host", default="127.0.0.1")
    parser.add_argument("--target-port", type=int, required=True)
    args = parser.parse_args()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.listen_host, args.listen_port))
    server.listen(256)
    print(
        f"proxy listening {args.listen_host}:{args.listen_port} -> "
        f"{args.target_host}:{args.target_port}",
        flush=True,
    )
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client, args), daemon=True).start()


if __name__ == "__main__":
    main()

param(
    [string]$DbUrl = "jdbc:mysql://127.0.0.1:13306/math_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false",
    [string]$DbUser = "math_agent",
    [string]$DbPassword = "123456",
    [string]$RabbitMqAddresses = "amqp://127.0.0.1:5672",
    [string]$WorkerId = "local-agent-worker"
)

# This starts the standalone Worker entry point: it consumes Agent commands but exposes no HTTP control-plane port.
$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$env:MATH_AGENT_DB_URL = $DbUrl
$env:MATH_AGENT_DB_USERNAME = $DbUser
$env:MATH_AGENT_DB_PASSWORD = $DbPassword
$env:SPRING_RABBITMQ_ADDRESSES = $RabbitMqAddresses
$env:MATH_AGENT_AGENT_WORKER_ID = $WorkerId
$env:MATH_AGENT_AGENT_WORKER_RUNTIME_ENABLED = "true"
Push-Location (Join-Path $Root "backend-java")
try {
    mvn "-Dspring-boot.run.main-class=com.doob.mathagent.AgentWorkerApplication" spring-boot:run
} finally {
    Pop-Location
}

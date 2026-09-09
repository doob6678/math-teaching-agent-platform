@echo off
rem LaTeX Studio background starter: survives terminal session teardown.
rem Log appends to logs\server.log, port 8764 (matches README and skill docs).
cd /d %~dp0
if not exist logs mkdir logs

rem Skip if port 8764 is already listening (no duplicate instance).
netstat -ano | findstr :8764 | findstr LISTENING >nul 2>&1
if %errorlevel%==0 (
  echo LaTeX Studio is already running: http://127.0.0.1:8764
  exit /b 0
)

start "latex-studio" /min cmd /c "python server.py --port 8764 >> logs\server.log 2>&1"
echo LaTeX Studio starting in background: http://127.0.0.1:8764

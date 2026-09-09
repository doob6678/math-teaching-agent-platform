@echo off
rem LaTeX Studio stopper: kill whatever listens on port 8764.
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8764 ^| findstr LISTENING') do (
  taskkill /F /PID %%a >nul 2>&1
)
echo LaTeX Studio stopped.

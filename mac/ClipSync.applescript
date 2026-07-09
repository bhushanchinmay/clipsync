set appPath to POSIX path of (path to me)
-- Strip trailing slash if present for dirname
if appPath ends with "/" then
    set appPath to text 1 thru -2 of appPath
end if
set scriptDir to do shell script "dirname " & quoted form of appPath

set pyScript to scriptDir & "/main.py"
set pyEnv to scriptDir & "/venv/bin/python"

set isRunning to false
try
    -- pgrep returns 0 if found, 1 if not
    do shell script "pgrep -f " & quoted form of pyScript
    set isRunning to true
end try

if isRunning then
    do shell script "pkill -f " & quoted form of pyScript
    display notification "Background synchronization has been stopped." with title "ClipSync Stopped"
else
    -- Redirect output to /tmp and run in background
    do shell script "nohup " & quoted form of pyEnv & " " & quoted form of pyScript & " > /tmp/clipsync.log 2>&1 &"
    display notification "Background synchronization is now running." with title "ClipSync Started"
end if

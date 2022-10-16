## luceedebug
### a step debugger for lucee

---
## build
There are two components:

 - A Java agent
 - A VsCode plugin

The java agent needs a particular invocation, and needs to be run as part of jvm/cf server startup.

The vscode client plugin is not released on the vscode plugin market place, so the
easiest way to run it is locally, in an 'extension development host'.

---

### Java  agent

```
# java agent
cd luceedebug
gradle build
ls build/libs/luceedebug.jar
```
Add the following to your java invocation

```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:9999

-javaagent:/abspath/to/luceedebug.jar=jdwpHost=localhost,jdwpPort=9999,cfHost=0.0.0.0,cfPort=10000,jarPath=/abspath/to/luceedebug.jar
```

Most java devs will be familiar with the `agentlib` part. We need JDWP running, listening on a socket on localhost. Luceedebug will attach to jdwp over a socket, running from within the same jvm. It stays attached for the life of the jvm.

Note that JDWP `address` and luceedebug's `jdwpHost`/`jdwpPort` must match.

The `cfPort` and `cfHost` options are the host/port that the vscode debugger attaches to. Note that jdwp can usually listen on localhost (the connection to jdwp from luceedebug is a loopback connection), but if your server is in a docker container, the cfHost might have to be something that will listen to external connections coming into the container (e.g. 0.0.0.0 or etc. Don't do that in production.).

The `jarPath` argument is the absolute path to the luceedebug.jar file. Unfortunately we have to say it's name twice! One tells the jvm which jar to use as a javagent, the second is an argument to the java agent about where to find the jar it will load debugging instrumentation from.

(There didn't seem to be an immediately obvious way to pull the name of "the current" jar file from an agent's `premain`, but maybe it's just been overlooked. If you know let us know!)

--- 

### VSCode client

```
# vscode client
cd vscode-client
npm install

npm run-build-windows # windows
npm run-build-linux   # linux

code . # open vscode in this dir
```

Steps to run the extension in vscode's "extension development host":
- Open vscode in this dir
- Go to the "run and debug" menu
- (looks like a bug with a play button)
- Run the "extension" configuration
- The extension development host window opens
- Load your cf project from that vscode instance


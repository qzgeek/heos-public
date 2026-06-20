#!/bin/sh
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then PRG="$link"
    else PRG=`dirname "$PRG"`"/$link"; fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`/" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVACMD="java"
which java >/dev/null 2>&1 || die "ERROR: no java"
eval set -- -Dorg.gradle.appname=GradleWrapperMain -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
exec "$JAVACMD" "$@"

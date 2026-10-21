#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
javac -encoding UTF-8 --release 17 -cp 'provided/servlet-api.jar:src/main/webapp/WEB-INF/lib/gson-2.13.2.jar' -d build/classes @sources.txt
rm -rf build/war
mkdir -p build/war/WEB-INF/classes
cp -R src/main/webapp/. build/war/
cp -R build/classes/. build/war/WEB-INF/classes/
jar --create --file GenreShiritoriWeb.war -C build/war .

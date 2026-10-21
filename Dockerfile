# Tomcat 9 / javax.servlet / Java 17
FROM tomcat:9.0-jdk17-temurin AS build
WORKDIR /build
COPY provided/servlet-api.jar provided/servlet-api.jar
COPY src src
COPY sources.txt sources.txt
RUN mkdir -p classes stage/WEB-INF/classes \
 && javac -encoding UTF-8 --release 17 -cp "provided/servlet-api.jar:src/main/webapp/WEB-INF/lib/gson-2.13.2.jar" -d classes @sources.txt \
 && cp -R src/main/webapp/. stage/ \
 && cp -R classes/. stage/WEB-INF/classes/ \
 && jar --create --file /build/app.war -C stage .
FROM tomcat:9.0-jdk17-temurin
COPY --from=build /build/app.war /usr/local/tomcat/webapps/ROOT.war
ENV CATALINA_OPTS="-Dshiritori.dataDir=/data"
VOLUME ["/data"]
EXPOSE 8080
CMD ["catalina.sh", "run"]

FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:35ab043a5ae07528500e3f12c8ffe9c578a9dade2bfd8a9040f5b25f09e6138a
ENV TZ="Europe/Oslo"
COPY build/libs/app.jar app.jar
CMD ["-jar","app.jar"]
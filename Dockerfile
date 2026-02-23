FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:2b210f8ade2034664fb6c7a38f503f84e8bfd1d5f9be7fdab7a63ec80dcf3780
ENV TZ="Europe/Oslo"
COPY build/libs/app.jar app.jar
CMD ["-jar","app.jar"]
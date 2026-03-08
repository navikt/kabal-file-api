FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:bc9cf92fa2a4f1ea4ba6d84ed4b153c00a1c4dec168d2bb0b24b69dabdf216c8
ENV TZ="Europe/Oslo"
COPY build/libs/app.jar app.jar
CMD ["-jar","app.jar"]
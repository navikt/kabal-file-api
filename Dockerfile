FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:2ab80d349d0f4a476f444ed7e2ca0f5080f74cf5e5dac7ed25dd4ea36a40f612
ENV TZ="Europe/Oslo"
COPY build/libs/app.jar app.jar
CMD ["-jar","app.jar"]
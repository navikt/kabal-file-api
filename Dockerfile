FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:b6252714c5b59256b44f51fa5adcf0b1725d2daf425e3824236efbea1a0d97d4
ENV TZ="Europe/Oslo"
COPY build/libs/app.jar app.jar
CMD ["-jar","app.jar"]
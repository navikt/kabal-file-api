FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-25@sha256:30b1f1ad0c168c0e7ad993862ff4b6c3fe93461f9a21d970c8d4ef453c60df81
ENV TZ="Europe/Oslo"
COPY build/libs/app.jar app.jar
CMD ["-jar","app.jar"]

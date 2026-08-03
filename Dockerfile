FROM europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-21@sha256:342999d10a2d75bbf70bf436008bc090b1ddd2b77b935b97fcf15cbdbf8a8027
ENV TZ="Europe/Oslo"

# HEIC/HEIF images can't be decoded in pure Java, so we shell out to ImageMagick (built with the
# libheif delegate) to convert them to PNG before embedding into a PDF.
# NOTE: this requires an apk-capable base image (Wolfi/Chainguard). If the pinned `jre` image above
# does not include apk, switch to the corresponding `-dev` variant for the build or use a multi-stage
# build that copies the ImageMagick + libheif binaries in. `magick`/`convert` must be on PATH.
USER root
RUN apk add --no-cache imagemagick libheif
USER nonroot

# Point the app at the ImageMagick binary (ImageMagick 7 provides `magick`; classic provides `convert`).
ENV IMAGE_MAGICK_COMMAND="magick"

COPY build/libs/app.jar app.jar
CMD ["-jar","app.jar"]
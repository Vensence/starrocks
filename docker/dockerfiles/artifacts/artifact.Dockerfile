# This docker file build the Starrocks artifacts fe & be and package them into a busybox-based image
# Please run this command from the git repo root directory to build:
#
# Build a CentOS7 based artifact image:
#  > DOCKER_BUILDKIT=1 docker build --rm=true --build-arg builder=starrocks/dev-env-centos7:main-latest -f docker/dockerfiles/artifacts/artifact.Dockerfile -t starrocks/artifact-centos7:tag .
#
# Build a Ubuntu based artifact image:
#  > DOCKER_BUILDKIT=1 docker build --rm=true --build-arg builder=starrocks/dev-env-ubuntu:main-latest -f docker/dockerfiles/artifacts/artifact.Dockerfile -t starrocks/artifact-ubuntu:tag .

ARG builder=starrocks/dev-env-ubuntu:main-latest
ARG RELEASE_VERSION

FROM ${builder} as fe-builder
ARG RELEASE_VERSION
COPY . /build/starrocks
WORKDIR /build/starrocks
# clean and build Frontend and Spark Dpp application
RUN --mount=type=cache,target=/root/.m2/ STARROCKS_VERSION=${RELEASE_VERSION} MAVEN_OPTS="-Dmaven.artifact.threads=128" ./build.sh --fe --clean


FROM ${builder} as be-builder
ARG RELEASE_VERSION
# build Backend in different mode (build_type could be Release, DEBUG, or ASAN). Default value is Release.
ARG BUILD_TYPE=Release
COPY . /build/starrocks
WORKDIR /build/starrocks
RUN --mount=type=cache,target=/root/.m2/ STARROCKS_VERSION=${RELEASE_VERSION} BUILD_TYPE=${BUILD_TYPE} ./build.sh --be --use-staros --clean -j `nproc`


FROM busybox:latest
ARG RELEASE_VERSION

LABEL org.opencontainers.image.source="https://github.com/starrocks/starrocks"
LABEL org.starrocks.version=${RELEASE_VERSION:-"UNKNOWN"}

COPY --from=fe-builder /build/starrocks/output /release/fe_artifacts
COPY --from=be-builder /build/starrocks/output /release/be_artifacts

WORKDIR /release

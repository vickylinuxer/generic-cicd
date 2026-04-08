FROM ubuntu:22.04

LABEL maintainer="vignesh.rajendran@protonmail.com"
LABEL description="AUTOSAR build environment for CI/CD"

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    git \
    python3 \
    python3-pip \
    wget \
    curl \
    ca-certificates \
    gcc-arm-none-eabi \
    libnewlib-arm-none-eabi \
    && rm -rf /var/lib/apt/lists/*

# AUTOSAR toolchain and SDK paths (mount or install as needed)
# Example: docker run -v /path/to/autosar-sdk:/opt/autosar
ENV AUTOSAR_SDK=/opt/autosar

WORKDIR /workspace

CMD ["/bin/bash"]

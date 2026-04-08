FROM ubuntu:22.04

LABEL maintainer="vignesh.rajendran@protonmail.com"
LABEL description="QNX SDP build environment for CI/CD"

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
    && rm -rf /var/lib/apt/lists/*

# QNX SDP must be mounted or installed at /opt/qnx710
# Mount via: docker run -v /path/to/qnx710:/opt/qnx710
ENV QNX_HOST=/opt/qnx710/host/linux/x86_64
ENV QNX_TARGET=/opt/qnx710/target/qnx7
ENV PATH="${QNX_HOST}/usr/bin:${PATH}"

WORKDIR /workspace

CMD ["/bin/bash"]

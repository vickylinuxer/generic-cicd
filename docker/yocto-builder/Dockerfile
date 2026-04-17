FROM ubuntu:22.04@sha256:eb29ed27b0821dca09c2e28b39135e185fc1302036427d5f4d70a41ce8fd7659

LABEL description="Yocto Project build environment"
LABEL org.opencontainers.image.title="yocto-builder"

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    gawk \
    wget \
    git \
    diffstat \
    unzip \
    texinfo \
    gcc \
    build-essential \
    chrpath \
    socat \
    cpio \
    python3 \
    python3-pip \
    python3-pexpect \
    python3-git \
    python3-jinja2 \
    python3-subunit \
    xz-utils \
    debianutils \
    iputils-ping \
    zstd \
    liblz4-tool \
    file \
    locales \
    libacl1 \
    ca-certificates \
    lz4 \
    curl \
    && rm -rf /var/lib/apt/lists/*

RUN locale-gen en_US.UTF-8
ENV LANG=en_US.UTF-8
ENV LC_ALL=en_US.UTF-8

# Jenkins Docker plugin runs as the agent's UID and creates /home/<user>.
# Make /home writable so any mapped user can create their home directory.
RUN useradd -m builder && chmod 777 /home
USER builder

WORKDIR /workspace

CMD ["/bin/bash"]

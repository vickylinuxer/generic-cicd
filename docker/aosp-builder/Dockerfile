FROM ubuntu:22.04@sha256:eb29ed27b0821dca09c2e28b39135e185fc1302036427d5f4d70a41ce8fd7659

LABEL description="AOSP build environment"
LABEL org.opencontainers.image.title="aosp-builder"

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    git-core \
    gnupg \
    flex \
    bison \
    build-essential \
    zip \
    curl \
    zlib1g-dev \
    libc6-dev-i386 \
    x11proto-core-dev \
    libx11-dev \
    lib32z1-dev \
    libgl1-mesa-dev \
    libxml2-utils \
    xsltproc \
    unzip \
    fontconfig \
    libncurses5 \
    ccache \
    python3 \
    python-is-python3 \
    openjdk-17-jdk-headless \
    rsync \
    wget \
    ca-certificates \
    bc \
    coreutils \
    dosfstools \
    e2fsprogs \
    fdisk \
    kmod \
    mtools \
    openssl \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# Install repo tool
RUN curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo > /usr/local/bin/repo \
    && chmod a+rx /usr/local/bin/repo

# Git color off by default; user.name and user.email are set at runtime
# via environment.env in project YAML (GIT_USER_NAME, GIT_USER_EMAIL)
RUN git config --global color.ui false

ENV USE_CCACHE=1

# Jenkins Docker plugin runs as the agent's UID and creates /home/<user>.
# Make /home writable so any mapped user can create their home directory.
RUN useradd -m builder && chmod 777 /home
USER builder

WORKDIR /workspace

CMD ["/bin/bash"]

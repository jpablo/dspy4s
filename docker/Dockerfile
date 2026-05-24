# Reusable Linux dev host for JetBrains Gateway remote development.
# Project-agnostic: project sources are bind-mounted at runtime under /home/dev/.
# Multi-arch (linux/amd64, linux/arm64) via Eclipse Temurin.
FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# Base OS + SSH + libs the JetBrains remote backend expects at runtime.
RUN apt-get update && apt-get install -y --no-install-recommends \
      openssh-server sudo tini ca-certificates curl gnupg \
      git less unzip zip procps locales \
      fontconfig libfreetype6 libxext6 libxrender1 libxtst6 libxi6 \
      libxkbcommon0 libxcb1 libxrandr2 \
    && rm -rf /var/lib/apt/lists/*

# sbt (architecture-independent launcher) from the official Scala apt repo.
RUN install -d -m 0755 /etc/apt/keyrings \
 && curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" \
      | gpg --dearmor -o /etc/apt/keyrings/sbt.gpg \
 && echo "deb [signed-by=/etc/apt/keyrings/sbt.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" \
      > /etc/apt/sources.list.d/sbt.list \
 && apt-get update && apt-get install -y --no-install-recommends sbt \
 && rm -rf /var/lib/apt/lists/*

# Dev user. UID/GID 1000 matches typical macOS Docker Desktop bind-mount mappings.
ARG USERNAME=dev
ARG UID=1000
ARG GID=1000
RUN groupadd -g "${GID}" "${USERNAME}" \
 && useradd -m -u "${UID}" -g "${GID}" -s /bin/bash "${USERNAME}" \
 && echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${USERNAME} \
 && chmod 0440 /etc/sudoers.d/${USERNAME}

# Make JAVA_HOME / PATH visible to sshd-spawned shells (Docker ENV is not inherited
# by sshd children). /etc/environment is read by PAM; profile.d covers login shells.
RUN printf 'JAVA_HOME=/opt/java/openjdk\nPATH="/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"\n' > /etc/environment \
 && printf 'export JAVA_HOME=/opt/java/openjdk\nexport PATH="$JAVA_HOME/bin:$PATH"\n' > /etc/profile.d/java.sh \
 && chmod 0644 /etc/profile.d/java.sh

# SSH daemon: keys only, no root login. Allow agent + X11 fwd for convenience.
RUN mkdir -p /run/sshd \
 && sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?ChallengeResponseAuthentication.*/ChallengeResponseAuthentication no/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?PubkeyAuthentication.*/PubkeyAuthentication yes/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?AllowAgentForwarding.*/AllowAgentForwarding yes/' /etc/ssh/sshd_config \
 && sed -i 's/^#\?X11Forwarding.*/X11Forwarding yes/' /etc/ssh/sshd_config

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

EXPOSE 22
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]

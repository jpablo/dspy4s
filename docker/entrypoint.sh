#!/usr/bin/env bash
set -euo pipefail

USERNAME="${USERNAME:-dev}"
USER_HOME="/home/${USERNAME}"

# Named-volume mount points are created by the Docker engine as root before this
# entrypoint runs. Reclaim them for the dev user so sbt / Gateway / coursier can
# write to their caches. Top-level only — avoids slow recursive chown on big caches.
for d in .cache .sbt .ivy2 .local .local/share .local/share/coursier; do
  if [ -d "${USER_HOME}/${d}" ]; then
    chown "${USERNAME}:${USERNAME}" "${USER_HOME}/${d}"
  fi
done

# Surface the bind-mounted project in the dev user's home so it's visible on `ls ~`.
if [ -d /workspace ] && [ ! -e "${USER_HOME}/workspace" ]; then
  ln -s /workspace "${USER_HOME}/workspace"
  chown -h "${USERNAME}:${USERNAME}" "${USER_HOME}/workspace"
fi

# Install authorized_keys from the bind-mounted source with sshd-required perms.
if [ -f /keys/authorized_keys ]; then
  install -d -m 700 -o "${USERNAME}" -g "${USERNAME}" "${USER_HOME}/.ssh"
  install -m 600 -o "${USERNAME}" -g "${USERNAME}" /keys/authorized_keys "${USER_HOME}/.ssh/authorized_keys"
else
  echo "WARN: /keys/authorized_keys not mounted — SSH login will fail." >&2
fi

# Generate host keys on first boot if missing.
ssh-keygen -A

exec /usr/sbin/sshd -D -e

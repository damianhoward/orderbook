# Deployment

The live order book at **https://orderbook.damianhoward.com** is deployed automatically on
every merge to `main` by [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml).

## Pipeline

1. **Build once.** The workflow runs the full quality gate (`clean build`) and packages
   the `installDist` distribution into a single artifact — the exact bytes that ship.
   `build` rather than `check`, because `check` does not depend on `jar` — so packaging
   sits outside it, and a gate that cannot see whether packaging works is not gating a
   pipeline whose output is a package.
2. **Ask the box for a release.** The deploy job sends the service name, commit, health port and
   budgets, with the bundle on stdin, over SSH — pinning the host key from
   [`known_hosts.pub`](known_hosts.pub) rather than trusting whatever answers on the address. The
   release unpacks into `/srv/orderbook/releases/<commit>` and `/srv/orderbook/current` is moved
   onto it with a symlink rename, so a restart can never see a half-copied install.
3. **Verify, or roll back.** A `/readyz` check gates success. If the new release does not come
   up, the same script flips the symlink back to the previous release and restarts — the
   decision is made on the box, so a runner that dies mid-deploy cannot leave a broken
   release serving. Three releases are retained.

That script is not sent from here. It is a root-owned script on the box, kept with the box's
other privileged configuration, which is what allows the key to be restricted to running it.

Secrets (`DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`) live in GitHub Actions, never in
the repo. `DEPLOY_SSH_KEY` is a key of CI's own, not the operator's, and on the box it is pinned
to a forced command: it can ask for a release and can do nothing else — no shell, no file copy,
no port forward. The account behind it may run exactly one command as root, `systemctl restart
orderbook`.

## Topology

A systemd-managed JVM behind Caddy, on a 1 GB micro VM:

- **The systemd unit** runs the `installDist` launcher as a non-root user with
  `Restart=on-failure` and a capped heap (`-Xmx256m`). Logs go to `journalctl`. The unit is not in
  this repository: a unit file is a request to run anything as anyone, so a deploy account able to
  install one holds root by another name. It is owned as host configuration and applied by an
  operator, for the same reason the Caddyfile below is.
  Host-specific config the unit shouldn't hard-code — the Kafka egress bootstrap address and
  the SCRAM-SHA-256 credentials it authenticates with — is read from a root-only
  `EnvironmentFile` on the box, declared optional so that when it is absent the server starts
  anyway with the egress off rather than failing to boot.
- **Caddy** reverse-proxies `localhost:8080` and auto-provisions a Let's Encrypt certificate;
  `flush_interval -1` keeps SSE streams unbuffered. The host's Caddy configuration is
  version-controlled, but not here and not by a deploy: it covers every site on the host, so it
  is owned as one whole file by the private infrastructure repository and installed by an
  operator-run script that validates it first. A bad Caddyfile takes down every site on the box
  at once, which is not a thing a service deploy should be able to do as a side effect.

The server binds loopback, so it is reachable only through that proxy. Nothing here is exposed
directly.

systemd + Caddy rather than Docker: the Docker daemon is too heavy for the 1 GB box's
memory budget. Both the unit and the proxy configuration are applied from the infrastructure
repository, so the host is reproducible rather than hand-edited either way.

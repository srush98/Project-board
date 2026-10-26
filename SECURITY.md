# Security Policy

This document describes the security controls in the Boardgame DevSecOps pipeline,
the risks that have been consciously accepted, and the known limitations of this
lab environment. Every exception is tracked as a Jira ticket with an owner and a
review date.

## Security controls in the delivery pipeline

| Stage | Control | Enforcement |
|---|---|---|
| SonarQube Analysis + Quality Gate | Static analysis: bugs, code smells, security hotspots, coverage on new code | **Blocks** the pipeline if the Quality Gate fails |
| Trivy FS Scan | Dependency CVEs and committed secrets in the source tree | Report always; **blocks** on CRITICAL fixable findings |
| Trivy Image Scan | OS-package and Java-library CVEs in the container image | Report always; **blocks** on CRITICAL fixable findings |
| KubeAudit (manifests) | Kubernetes security controls on the rendered manifest, before deployment | **Blocks** deployment on any error-level finding |
| Verify Deployment | Rollout status + smoke test through the load balancer (HTTP 200) | **Blocks**; automatic rollback if the deployment fails after apply |
| KubeAudit (live) | Audit of the running namespace | Report only (see KAN-3) |

## Supply-chain controls

- Security tools are installed at pinned versions and verified with SHA-256 checksums
  (Trivy v0.75.0, KubeAudit v0.22.2, kubectl). Trivy is pinned because of the
  March 2026 Trivy supply-chain compromise (CVE-2026-33634).
- Container images use immutable tags (`<build-number>-<git-sha>`); `latest` is never deployed.
- The Spring Boot build is reproducible: the same source produces an identical artifact and image.

## Runtime hardening

- Container runs as non-root (UID 10001) on a JRE-only base image (`eclipse-temurin:17-jre-noble`).
- `allowPrivilegeEscalation: false`, all Linux capabilities dropped, read-only root filesystem
  (only `/tmp` is writable), seccomp `RuntimeDefault`.
- Service-account token mounting disabled; CPU and memory requests and limits set.
- EKS worker nodes run in private subnets with no public IPs; the cluster API endpoint is
  private for in-VPC access and IP-restricted on its public side.
- Jenkins has namespace-scoped access only (`AmazonEKSEditPolicy` on `boardgame`) via its
  IAM role. No static AWS keys or kubeconfig files are stored.

## Secrets handling

- All secrets (SonarQube token, Nexus, Docker Hub, Jira, Gmail) are stored in the Jenkins
  credentials store and injected at runtime. They are masked in logs and never committed.
- Least-privilege tokens: SonarQube project analysis token; Docker Hub read/write token for
  Jenkins and a separate read-only token for Kubernetes; a dedicated Nexus `jenkins` user
  limited to the Maven repositories.
- Server-side secrets (SonarQube database password, Grafana admin password) live in `.env`
  files with `chmod 600` and are excluded from Git.

---

## Accepted risks

There are no open risk acceptances.

### KAN-2: Spring Boot 2.5.6 CVEs (remediated)

**Original risk:** 26 CRITICAL fixable CVEs inherited from Spring Boot 2.5.6. The Trivy
file-system gate was temporarily set to `UNSTABLE`, and the image gate was limited to OS
packages, so the pipeline could be evaluated.

**Remediation (2026-10-26):**

* Upgraded Spring Boot 2.5.6 → 3.5 → 4.1.2 and Java 11 → 17, using OpenRewrite for the
  `javax.*` to `jakarta.*` migration and the Boot 4.0 starter changes.
* Migrated Spring Security from `WebSecurityConfigurerAdapter` to a `SecurityFilterChain`
  bean; demo users are now created through `JdbcUserDetailsManager` with BCrypt-hashed passwords.
* Restored both hard Trivy gates: the `catchError` wrapper was removed from the file-system
  scan, and the image scan now gates on Java libraries as well as OS packages.

**Closure:** Close the ticket once a pipeline run on the upgraded build passes both Trivy gates.

**Owner:** Srushti

---

## Known gaps

### KAN-3: Network Policy Gap

**Finding:** KubeAudit reports `MissingDefaultDenyIngressAndEgressNetworkPolicy` in the
`boardgame` namespace.

**Risk:** Pods can communicate with other cluster workloads, increasing lateral movement
risk if a pod is compromised.

**Current Mitigations:**

* Single-application cluster.
* Nodes in private subnets.
* Load balancer restricted using `loadBalancerSourceRanges`.
* Pods run as non-root with dropped capabilities and a read-only root filesystem.
* Service-account token mounting disabled.

**Remediation:** Enable `enableNetworkPolicy` in the VPC CNI add-on. Apply default-deny
ingress and egress policies, then allow ingress on port 8080 and DNS egress.
Verify that enforcement actually blocks traffic: on EKS, NetworkPolicies are not enforced
unless this setting is enabled.

**Owner:** Srushti

**Target Date:** 2026-11-24

### KAN-5: CSRF protection and frame options disabled

**Finding:** `SecurityConfig` disables CSRF protection and the `X-Frame-Options` header. The
H2 web console is enabled (`spring.h2.console.enabled=true`, `web-allow-others=true`) and
reachable without login through the `/**` `permitAll` rule. These settings were carried over
unchanged from the original application during the KAN-2 upgrade.

**Risk:** Authenticated users can be tricked into submitting state-changing requests
(cross-site request forgery). Pages can be framed by other sites (clickjacking). Anyone who
can reach the application can open the H2 console and read or change the database.

**Current Mitigations:**

* Load balancer restricted to allow-listed IPs using `loadBalancerSourceRanges`.
* Demo data only, stored in an in-memory H2 database that is reset on every restart.
* Environment is torn down after evaluation.

**Remediation:** Remove `.csrf(csrf -> csrf.disable())`; Thymeleaf forms that use `th:action`
include the CSRF token automatically. Change frame options to `sameOrigin()`. Disable the H2
console outside local development, for example in a `dev` profile only.

**Owner:** Srushti

**Target Date:** TBD

---

## Known limitations of this lab environment

These are deliberate trade-offs for cost and simplicity. Each lists the production fix.

| Limitation | Why accepted here | Production fix |
|---|---|---|
| Jenkins, SonarQube, Nexus and Grafana served over plain HTTP | No domain name for a TLS certificate | ALB + ACM certificate, or reverse proxy with Let's Encrypt |
| CI tool servers in a public subnet (security groups restrict access to specific IPs) | Avoids bastion/ALB cost and complexity | Private subnets with SSM Session Manager and an internal ALB |
| Builds run on the Jenkins controller; `jenkins` user in the `docker` group (root-equivalent) | No extra agent instances | Ephemeral build agents; rootless image builds (Kaniko / BuildKit rootless) |
| KubeAudit is archived upstream (October 2024) | Required by the assignment brief | Maintained scanner (Kubescape, Checkov, `trivy config`) plus admission control (Kyverno / Pod Security Admission) |
| Jenkins `/prometheus/` metrics endpoint is unauthenticated | Reachable only from allow-listed security groups | Enable endpoint authentication in the Prometheus plugin |
| `sonarqube:community` image tag is not pinned | Simplicity for the course | Pin exact versions or image digests |
| Single NAT gateway (one AZ) | Halves NAT cost | One NAT gateway per AZ |
| Demo login credentials published in the upstream README | Sample application data | Remove from docs; real authentication backed by a secrets manager |
| No envelope encryption of Kubernetes Secrets with a customer-managed KMS key | KMS key cost | Enable EKS secrets encryption with a customer-managed key |
| Private Docker Hub repository with an image pull secret | Required registry choice | Amazon ECR with IAM-based pulls (no secret needed) |

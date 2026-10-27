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

## Application security controls

- Form-based login backed by `JdbcUserDetailsManager`; passwords stored as BCrypt hashes.
- Role-based URL rules: `/secured/**` and `/user/**` require `USER` or `MANAGER`;
  `/manager/**` requires `MANAGER`. Everything else is public (see the authorization gap below).
- CSRF protection is enabled (Spring Security default). Every Thymeleaf form uses `th:action`,
  so the token is included automatically. Logout is a `POST` form.
- `X-Frame-Options: DENY` and Spring Security's other default security headers are sent.
- Tests send CSRF tokens with `SecurityMockMvcRequestPostProcessors.csrf()`.

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

## Transport security (KAN-7)

- The application is served over HTTPS on a custom domain. TLS terminates at the AWS Network
  Load Balancer using an ACM certificate (`aws-load-balancer-ssl-cert`) on port 443.
- The NLB uses the `ELBSecurityPolicy-TLS13-1-2-2021-06` policy: TLS 1.3 and TLS 1.2 only,
  with legacy protocols and weak ciphers disabled.
- Traffic from the NLB to the pods on port 8080 is plain HTTP inside the VPC.
- Port 80 is still open and serves plain HTTP (see the known gap below).

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
* Pods run as non-root with dropped capabilities and a read-only root filesystem.
* Service-account token mounting disabled.

**Remediation:** Enable `enableNetworkPolicy` in the VPC CNI add-on. Apply default-deny
ingress and egress policies, then allow ingress on port 8080 and DNS egress.
Verify that enforcement actually blocks traffic: on EKS, NetworkPolicies are not enforced
unless this setting is enabled.

**Owner:** Srushti

**Target Date:** 2026-11-24

### KAN-5: CSRF protection, frame options and H2 console (partially remediated)

**Original finding:** `SecurityConfig` disabled CSRF protection and the `X-Frame-Options`
header. The H2 web console is enabled (`spring.h2.console.enabled=true`,
`web-allow-others=true`) and reachable without login through the `/**` `permitAll` rule.
These settings were carried over unchanged from the original application during the KAN-2
upgrade.

**Remediated (2026-10-26, PR #3):**

* Removed `.csrf(csrf -> csrf.disable())` and the `frameOptions` override, so Spring
  Security's defaults apply: CSRF tokens are required and `X-Frame-Options: DENY` is sent.
* Updated `TestController` to send CSRF tokens on `POST` requests.

**Still open:** The H2 console is still enabled and reachable at `/h2-console` without login.
With CSRF and `DENY` frame options in place, its browser UI no longer works, but the console
should not be exposed outside local development.

**Risk:** Low while the console is unusable, but a later change that relaxes CSRF or frame
options for the console would expose read and write access to the database. Since KAN-7 the
load balancer is no longer IP-restricted, so `/h2-console` is reachable from the internet.

**Current Mitigations:**

* CSRF protection and `X-Frame-Options: DENY` block the console's login form and frames.
* Demo data only, stored in an in-memory H2 database that is reset on every restart.

**Remediation:** Set `spring.h2.console.enabled=false` and remove `web-allow-others` from
`application.properties`; enable the console only in a local `dev` profile. Add a regression
test that sends a `POST` without a token and expects `403 Forbidden`.

**Owner:** Srushti

**Target Date:** TBD

### Authorization gaps on state-changing endpoints (ticket to be filed)

**Finding:** Several endpoints that change data fall under the `/**` `permitAll` rule because
they are not under `/secured/**` or `/manager/**`:

| Endpoint | Method | Effect |
|---|---|---|
| `/deleteReview/{id}` | GET | Deletes a review; intended for managers only |
| `/reviewAdded` | POST | Adds or edits a review |
| `/boardgameAdded` | POST | Adds a board game |
| `/boardgames` | POST | Adds a board game (JSON API) |

In addition, `/addUser` lets anyone register with any role, including `MANAGER`.

**Risk:** Unauthenticated visitors can add board games and add, edit or delete reviews.
`/deleteReview/{id}` is a `GET` request, so CSRF protection does not cover it: a link or
image tag on another site can delete reviews. Since KAN-7 removed the load balancer IP
allow-list (`loadBalancerSourceRanges`), any internet user can reach these endpoints.

**Current Mitigations:**

* CSRF tokens are required on the `POST` endpoints, which blocks cross-site form submissions.
* Demo data only, reset on every restart.

**Remediation:** Change `/deleteReview/{id}` to `POST` (or `DELETE`) and restrict it to
`MANAGER`. Require `USER` or `MANAGER` for `/reviewAdded`, `/boardgameAdded` and
`POST /boardgames`. Restrict self-registration to the `USER` role. Add tests that expect
anonymous requests to these endpoints to be redirected to the login page.

**Owner:** Srushti

**Target Date:** TBD

### KAN-7 follow-up: Plain HTTP still served on port 80

**Finding:** The load balancer listens on port 80 as well as 443, and both forward to the pod
on port 8080. An NLB cannot redirect HTTP to HTTPS, and the application does not send a
`Strict-Transport-Security` header. TLS terminates at the NLB, so the application sees every
request as HTTP and does not mark the session cookie `Secure`. The pipeline smoke test still
calls the load balancer over `http://`.

**Risk:** A user who opens the site over `http://` sends login credentials and the session
cookie in clear text. The session cookie can also leak over HTTP after an HTTPS login.

**Current Mitigations:**

* HTTPS is available with TLS 1.2+ on the custom domain.
* Demo data only.

**Remediation:** Remove port 80 from the Service, or move to an ALB (AWS Load Balancer
Controller Ingress) with an HTTP-to-HTTPS redirect. Once all traffic is HTTPS, enable HSTS
and set `server.servlet.session.cookie.secure=true`. Switch the smoke test to `https://` on
the custom domain.

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

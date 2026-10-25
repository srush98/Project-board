# KAN-2: Risk Acceptance

Risk: 26 CRITICAL fixable CVEs inherited from Spring Boot 2.5.6. See `trivy-fs-report.txt`.

Decision: Temporarily set the Trivy file-system gate to `UNSTABLE` instead of `FAIL` to complete the pipeline evaluation.

Compensating Controls:

* Application runs in private subnets with no public IP.
* External access is restricted to the load balancer.
* Demo data only, stored in an in-memory H2 database.
* Environment is torn down after evaluation.

Remediation: Upgrade to Spring Boot 3.x and rerun the Trivy scan.

Owner: Srushti

Expiry Date: 2026-11-24

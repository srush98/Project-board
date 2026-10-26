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

# KAN-3: Network Policy Gap

Finding: KubeAudit reports `MissingDefaultDenyIngressAndEgressNetworkPolicy` in the `boardgame` namespace.

Risk: Pods can communicate with other cluster workloads, increasing lateral movement risk if a pod is compromised.

Current Mitigations:

* Single-application cluster.
* Nodes in private subnets.
* Load balancer restricted using `loadBalancerSourceRanges`.
* Pods run as non-root with dropped capabilities and a read-only root filesystem.
* Service-account token mounting disabled.

Remediation: Enable `enableNetworkPolicy` in the VPC CNI add-on. Apply default-deny ingress and egress policies, then allow ingress on port 8080 and DNS egress. Verify policy enforcement.

Owner: Srushti

Target Date: 2026-11-24

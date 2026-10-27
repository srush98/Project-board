# Boardgame Listing Web App

## Description 

**Board Game Database Full-Stack Web Application.**
This web application displays lists of board games and their reviews. While anyone can view the board game lists and reviews, they are required to log in to add/ edit the board games and their reviews. The 'users' have the authority to add board games to the list and add reviews, and the 'managers' have the authority to edit/ delete the reviews on top of the authorities of users.  

## Technologies

- Java 17
- Spring Boot 4.1
- Amazon Web Services(AWS) EC2
- Thymeleaf
- Thymeleaf Fragments
- HTML5
- CSS
- JavaScript
- Spring MVC
- JDBC
- H2 Database Engine (In-memory)
- JUnit 5 test framework
- Spring Security 7
- Twitter Bootstrap
- Maven

## Features

- Full-Stack Application
- UI components created with Thymeleaf and styled with Twitter Bootstrap
- Authentication and authorization using Spring Security
  - Authentication by allowing the users to authenticate with a username and password
  - Passwords stored as BCrypt hashes
  - Authorization by granting different permissions based on the roles (non-members, users, and managers)
  - CSRF protection on all state-changing requests (Thymeleaf `th:action` forms include the token automatically)
  - Clickjacking protection through the `X-Frame-Options: DENY` header
- Different roles (non-members, users, and managers) with varying levels of permissions
  - Non-members only can see the boardgame lists and reviews
  - Users can add board games and write reviews
  - Managers can edit and delete the reviews
- Deployed the application on AWS EC2
- JUnit test framework for unit testing
- Spring MVC best practices to segregate views, controllers, and database packages
- JDBC for database connectivity and interaction
- CRUD (Create, Read, Update, Delete) operations for managing data in the database
- Schema.sql file to customize the schema and input initial data
- Thymeleaf Fragments to reduce redundancy of repeating HTML elements (head, footer, navigation)

## How to Run

Prerequisite: JDK 17 or later. Maven is not required; the project ships with the Maven wrapper.

1. Clone the repository
2. Start the application, either from your IDE or from the command line:
   ```sh
   ./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
   ```
3. Open http://localhost:8080
4. To use initial user data, use the following credentials.
  - username: bugs    |     password: bunny (user role)
  - username: daffy   |     password: duck  (manager role)
5. You can also sign-up as a new user and customize your role to play with the application! 😊

Run the tests with `./mvnw test`. A JaCoCo coverage report is written to `target/site/jacoco/`.

> **Note:** CSRF protection is enabled, so `POST` requests sent outside the browser (for example
> `curl` calls to the JSON API at `POST /boardgames`) are rejected with `403 Forbidden` unless
> they include a valid CSRF token. In MockMvc tests, add `.with(csrf())` to `POST` requests.
> The H2 console is no longer usable in the browser because of these protections; see KAN-5 in
> [SECURITY.md](SECURITY.md).

### Run with Docker

```sh
./mvnw package -DskipTests
docker build -t boardgame .
docker run -p 8080:8080 boardgame
```

The image runs on `eclipse-temurin:17-jre-noble` as a non-root user (UID 10001).

## CI/CD and security

The `Jenkinsfile` builds, tests and scans the application (SonarQube, Trivy, KubeAudit) before
deploying it to Amazon EKS using the manifests in `k8s/`. See [SECURITY.md](SECURITY.md) for the
security controls, accepted risks and known gaps.

## Upgrade history

| Version | Notes |
|---|---|
| Spring Boot 2.5.6, Java 11 | Original application |
| Spring Boot 3.5 | `javax.*` to `jakarta.*` migration; `WebSecurityConfigurerAdapter` replaced with a `SecurityFilterChain` bean |
| Spring Boot 4.0 → 4.1.2, Java 17 | Modular starters (`spring-boot-starter-webmvc`, test starters); resolves the CVEs tracked in KAN-2 |
| Security hardening (KAN-5) | CSRF protection and `X-Frame-Options` re-enabled (Spring Security defaults) |

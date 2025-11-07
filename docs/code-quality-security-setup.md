# Code Quality & Security Scanning Setup

This document explains how to configure and use the SonarQube and CodeQL scanning workflows for the Village Overhaul project.

## Overview

The project now includes two automated code analysis workflows:

1. **CodeQL** - Security vulnerability scanning (GitHub Advanced Security)
2. **SonarQube Community Edition** - Code quality analysis and technical debt tracking

## CodeQL Security Scanning

### What is CodeQL?

CodeQL is GitHub's semantic code analysis engine that helps find security vulnerabilities and coding errors. It's part of GitHub Advanced Security and is free for public repositories.

### Features

- Scans for security vulnerabilities (SQL injection, XSS, path traversal, etc.)
- Detects common coding errors and anti-patterns
- Runs automatically on push, pull requests, and weekly schedule
- Results appear in the Security tab of the repository

### Workflow Details

- **File**: `.github/workflows/codeql.yml`
- **Triggers**: 
  - Push to `main` and `**-village-overhaul` branches
  - Pull requests to `main`
  - Weekly schedule (Mondays at 00:00 UTC)
- **Query Packs**: `security-extended` and `security-and-quality`

### Viewing Results

1. Navigate to the **Security** tab in your GitHub repository
2. Click **Code scanning alerts**
3. Review any findings and take appropriate action

### Configuration

No additional configuration is required. CodeQL is enabled automatically for public repositories.

## SonarQube Code Quality Analysis

### What is SonarQube?

SonarQube is a comprehensive code quality platform that analyzes code for bugs, code smells, security vulnerabilities, and technical debt.

### Features

- Code coverage tracking (via JaCoCo)
- Detection of bugs and code smells
- Security hotspot identification
- Technical debt estimation
- Quality gate checks

### Workflow Details

- **File**: `.github/workflows/sonarqube.yml`
- **Triggers**:
  - Push to `main` and `**-village-overhaul` branches
  - Pull requests to `main`
- **Coverage**: Generated via JaCoCo plugin in Gradle

### Prerequisites

You need to configure two GitHub repository secrets:

1. **SONAR_TOKEN** - Authentication token from SonarQube/SonarCloud
2. **SONAR_HOST_URL** - URL of your SonarQube server

#### Option A: Using SonarCloud (Recommended for Open Source)

1. Go to [SonarCloud](https://sonarcloud.io/)
2. Sign in with your GitHub account
3. Click **+** → **Analyze new project**
4. Select your repository
5. Go to **Administration** → **Analysis Method** → **GitHub Actions**
6. Copy the `SONAR_TOKEN` provided
7. In your GitHub repository, go to **Settings** → **Secrets and variables** → **Actions**
8. Create secret `SONAR_TOKEN` with the token from SonarCloud
9. Create secret `SONAR_HOST_URL` with value: `https://sonarcloud.io`

#### Option B: Using Self-Hosted SonarQube Community Edition

1. Install and configure SonarQube Community Edition on your server
2. Create a new project in SonarQube
3. Generate an authentication token
4. In your GitHub repository, go to **Settings** → **Secrets and variables** → **Actions**
5. Create secret `SONAR_TOKEN` with your SonarQube token
6. Create secret `SONAR_HOST_URL` with your SonarQube server URL (e.g., `https://sonar.example.com`)

### Project Configuration

The SonarQube analysis is configured in `sonar-project.properties`:

```properties
sonar.projectKey=deavisdude_billineire
sonar.projectName=Village Overhaul
sonar.sources=plugin/src/main/java
sonar.tests=plugin/src/test/java
sonar.java.source=17
sonar.coverage.jacoco.xmlReportPaths=plugin/build/reports/jacoco/test/jacocoTestReport.xml
```

You may need to update the `sonar.projectKey` and `sonar.organization` values to match your SonarCloud organization.

### Viewing Results

#### SonarCloud
1. Navigate to [SonarCloud](https://sonarcloud.io/)
2. Select your project
3. View the dashboard with metrics, issues, and coverage

#### Self-Hosted SonarQube
1. Navigate to your SonarQube server URL
2. Log in and select your project
3. View the dashboard with metrics, issues, and coverage

### Quality Gate

The workflow includes a quality gate check that will fail the build if code quality standards are not met. You can configure quality gate conditions in your SonarQube/SonarCloud project settings.

## Code Coverage (JaCoCo)

The project now includes JaCoCo for code coverage reporting.

### Configuration

Added to `plugin/build.gradle`:

```gradle
plugins {
    id 'jacoco'
}

jacoco {
    toolVersion = "0.8.11"
}

jacocoTestReport {
    dependsOn test
    reports {
        xml.required = true
        html.required = true
    }
}
```

### Viewing Coverage Reports Locally

After running tests, coverage reports are available at:
- XML: `plugin/build/reports/jacoco/test/jacocoTestReport.xml`
- HTML: `plugin/build/reports/jacoco/test/html/index.html`

Generate reports with:
```bash
cd plugin
./gradlew test jacocoTestReport
```

## Workflow Status Badges

Add these badges to your README.md to show workflow status:

### CodeQL
```markdown
![CodeQL](https://github.com/deavisdude/billineire/workflows/CodeQL%20Security%20Analysis/badge.svg)
```

### SonarQube (SonarCloud)
```markdown
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=deavisdude_billineire&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=deavisdude_billineire)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=deavisdude_billineire&metric=coverage)](https://sonarcloud.io/summary/new_code?id=deavisdude_billineire)
```

## Troubleshooting

### CodeQL Issues

**Problem**: CodeQL analysis fails with build errors
- **Solution**: Check that the Java build succeeds independently. CodeQL requires a successful build to analyze the code.

**Problem**: No vulnerabilities found
- **Solution**: This is good! CodeQL found no security issues. The workflow still succeeds.

### SonarQube Issues

**Problem**: "SONAR_TOKEN secret not found"
- **Solution**: Ensure you've configured the `SONAR_TOKEN` and `SONAR_HOST_URL` secrets in your repository settings.

**Problem**: Quality gate fails
- **Solution**: Review the issues in SonarQube/SonarCloud and address code quality concerns. You can also adjust quality gate thresholds.

**Problem**: Coverage report not found
- **Solution**: Ensure tests run successfully and JaCoCo generates the XML report at the expected path.

## Best Practices

1. **Review alerts promptly**: Check CodeQL and SonarQube results for each PR
2. **Don't ignore warnings**: Address or document why issues can be dismissed
3. **Monitor trends**: Track code quality metrics over time in SonarQube
4. **Set quality gates**: Configure appropriate thresholds for your project
5. **Keep dependencies updated**: Regularly update the analysis tools

## Additional Resources

- [CodeQL Documentation](https://codeql.github.com/docs/)
- [SonarQube Documentation](https://docs.sonarqube.org/)
- [SonarCloud Documentation](https://docs.sonarcloud.io/)
- [JaCoCo Documentation](https://www.jacoco.org/jacoco/trunk/doc/)

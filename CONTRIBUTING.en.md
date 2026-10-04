# Contributing

[中文](CONTRIBUTING.md) | [English](CONTRIBUTING.en.md)

Before a substantial change, open a GitHub issue describing the problem, compatibility impact, and expected behavior.
Small changes with clear boundaries may be submitted directly as pull requests.

## Development environment

- JDK 21 or later
- Maven 3.6.3 or later

Maven accepts JDK versions in `[21,)`, without an upper bound. Keep `release=21` when building on a newer JDK so artifacts
retain their minimum Java requirement.
Source builds use Lombok `1.18.42`. Use JDK 21 or 25; other JDK compilers must be compatible with this annotation processor.

```bash
mvn -B -ntp clean verify
```

## Code requirements

- Keep the core library independent and written in pure Java, without application-container coupling.
- Explain binary and wire compatibility for public API changes.
- Write code comments in Chinese; preserve identifiers, protocol names, and configuration keys.
- Method comments state their business purpose and explain meaningful parameters, return boundaries, failures, and side effects.
- Comments describe current business intent, design reasons, boundaries, and failure consequences. Do not include internal
  development labels, tool attribution, generation or release history, or temporary iteration markers.
- Public changes contain product source and user resources, without local helper code or execution records.
- Exclude generated directories, IDE metadata, keys, tokens, logs, and machine-specific configuration.

## Changes and licensing

Commit messages explain what changed and why. Pull requests include a change summary and compatibility implications.
Contributions are provided under the project's `Apache-2.0 OR MIT` dual license.

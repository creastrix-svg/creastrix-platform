# User Profile

## Purpose

A User Profile represents the personal information of a User.

A User Profile stores personal data that is independent of authentication and authorization.

Separating personal information from the User entity allows the platform to evolve independently from identity and security concerns.

## Responsibilities

A User Profile is responsible for:

- storing the User's personal information;
- storing the User's country and locale preferences;
- storing contact information that is not used for authentication;
- providing personal information for platform interactions.

## Relationships

A User Profile:

- belongs to exactly one User;
- cannot exist without a User.

## Business Rules

- A User Profile is created together with its User.
- A User Profile stores personal information only and does not store authentication or authorization data.
- Authentication email, password credentials, MFA settings, and external login identifiers belong to User.
- Contact information stored in a User Profile must not be used as the User's authentication identity.
- A User may update personal information in the User Profile without changing the User's identity.

## Invariants

- A User Profile always belongs to exactly one User.
- A User always has exactly one User Profile.
- A User Profile cannot exist independently from its User.
- A User Profile cannot contain authentication or authorization data.
- Deleting or deactivating a User Profile independently from its User is not allowed.

## Notes

Authentication and identity data belong to User, while personal information belongs to User Profile.

Business-specific information is stored in dedicated profile entities such as Designer Profile and Manufacturer Profile.

---

Status: APPROVED
Version: 1.0

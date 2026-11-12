# Media storage: local now, S3 candidate for Frankfurt

Two independent local features use the immutable file store. The [synthetic cart](account-cart-local-run.md) keeps the buyer's applied PNG and custom font private. The [catalog photo workflow](../local-catalog.md#publish-a-local-seller-photo) retains the operator's original and publishes only two generated renditions through the active variant cover. They use separate directories and databases. PostgreSQL stores metadata and references, not file bodies. A digest, filename or key is not a read permission. Neither workflow is a deployed media service.

## What belongs in each store

| Media | Private source | Possible published derivative, only after approval |
| --- | --- | --- |
| Avatar | Uploaded photo, including its original metadata | Cropped, resized avatar with metadata stripped |
| Product photo | Workspace-controlled master photo | Catalogue thumbnail or display-sized photo |
| Custom font | Buyer’s uploaded font; no redistribution implied | Rendered lettering, not the reusable font file |
| Drawing or blueprint | Editable design or manufacturing file | Flattened, approved preview with private layers removed |
| 3D model | Master mesh, textures and working files | Approved render or separately cleared viewer model |

The product-photo row is implemented only in the dedicated local single-operator catalog. Avatars, published drawing/model derivatives and third-party seller access remain future work. A buyer/cart preview remains private; rendering it does not authorise publication. A public-facing derivative has a separate identity from its source; withdrawing a product photo must not alter the original.

## Adding the next media type

Use the [small immutable-object port](../../backend/src/main/java/com/creastrix/platform/storage/ImmutableObjectStore.java) through a feature-specific service. The service resolves who may upload or read, admits a bounded format and reserves a server-generated key. Write and verify the bytes **before** linking the object to a business record; keep recoverable staging metadata when a write is interrupted. A new avatar or product photo gets a new version. Do not overwrite an object already referenced by a cart, listing or design.

The local driver handles bounded files in memory (16 MiB per object; the cart restricts images to 10 MiB and fonts to 2 MiB). It is **not** the upload path for large CAD assemblies or textured 3D models. Those need a separate streaming or multipart protocol with per-owner quotas, validation in isolation, and a verified completion step. A product’s browser preview is not automatically its downloadable manufacturing source.

## Inactive infrastructure

The [CloudFormation candidate](../../infra/media-storage/storage.cfn.json) describes two separate, provider-named S3 buckets: `PrivateOriginals` and `PublishedDerivatives`. **Both are private.** “Published” means the application approved that representation for distribution, not that the bucket allows anonymous reads. The template adds no IAM grants, credentials, CDN, public website, S3 adapter or application configuration.

All four resources (buckets and policies) share one [CloudFormation condition](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/conditions-section-structure.html): storage must be explicitly enabled **and** the stack region must be `eu-central-1`. Otherwise no resources in this template are provisioned. Each [bucket](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-s3-bucket.html) requests versioning, all four public-access blocks, disabled ACLs (`BucketOwnerEnforced`) and [SSE-S3 default encryption](https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingServerSideEncryption.html). Its deny-only [bucket policy](https://docs.aws.amazon.com/AmazonS3/latest/userguide/example-bucket-policies.html#example-bucket-policies-HTTP-HTTPS) rejects non-TLS requests to the bucket and its objects, deletion of buckets/objects/versions, and lifecycle-rule changes. These controls do not make the service write-once or protect against an administrator replacing the policy.

Both buckets **and their protective policies** use [DeletionPolicy: Retain](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-attribute-deletionpolicy.html) and [UpdateReplacePolicy: Retain](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-attribute-updatereplacepolicy.html). Disabling or replacing resources is not an undo: retained buckets still cost money and may fall out of stack management. Re-enabling the template need not reconnect the old data.

Offline check from the repository root (Python standard library; no credentials or network):

```sh
python3 -B infra/media-storage/test_storage.py
```

The tests evaluate enabled/disabled regions and both bucket policies, including deliberate regressions. They are not an AWS deployment test.

## Recovery and deletion boundaries

For local recovery, preserve a consistent pair: the synthetic PostgreSQL backup **and** the matching private asset directory identified by the [launcher](../../backend/tools/account-cart-local.sh). Restore into an isolated environment with the same owner-only permissions. Check each needed revision’s references against the restored file sizes and digests; test an authorised read and an unauthorised denial before reopening access. A database dump alone cannot restore media. Missing or mismatched content must remain unavailable; never substitute another buyer’s matching-looking file.

A future S3 adapter must keep the exact bucket, opaque key **and version ID** with the owner-authorised metadata. Recover a pinned, verified version, not whichever version is current. S3 supports [retrieval and non-destructive copying of a prior version](https://docs.aws.amazon.com/AmazonS3/latest/userguide/RestoringPreviousVersions.html); versioning alone is not an independent backup of the objects or PostgreSQL.

The [accepted private-design lifecycle](account-cart-activation.md#accepted-storage-decision) uses server instants:

- An unfinished upload **never linked** to a design, cart or pending import becomes eligible 24 hours after creation.
- A formerly linked file becomes eligible **7 days after the last durable unlink commits**. An authorised relink cancels that clock; a subsequent last unlink starts a new one.
- Any surviving reference, uncertain commit or unknown history blocks deletion. Eligibility is not physical deletion. A collector would have to recheck references and serialize removal against relinking.

The template has no expiration rules or collector. Do not apply those clocks to published media, saved designs, receipts, backups or account erasure. Before any deployment or real data: approve IAM and delivery paths, media validation, publication/withdrawal, deletion/erasure policy, backup retention and restore drill, operational ownership and cost limits. This candidate neither performs that work nor authorises AWS changes.

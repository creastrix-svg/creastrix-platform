"""Offline policy contract for storage.cfn.json. Standard library only; no AWS calls."""
import copy
import itertools
import json
import unittest
from pathlib import Path


def unique(pairs):
    obj = dict(pairs)
    if len(pairs) != len(obj):
        raise ValueError("Duplicate JSON key")
    return obj


TEMPLATE = json.loads(Path(__file__).with_name("storage.cfn.json").read_text(), object_pairs_hook=unique)
BUCKETS = {"PrivateOriginals": "private-originals", "PublishedDerivatives": "published-derivatives"}
PUBLIC_BLOCK = {"BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets"}
NO_DELETE = {"s3:DeleteBucket", "s3:DeleteObject", "s3:DeleteObjectVersion", "s3:PutLifecycleConfiguration"}


def condition(expr, values):
    """Evaluate only the boolean subset used by this template; reject others."""
    if isinstance(expr, str):
        return expr
    (op, arg), = expr.items()
    if op == "Ref":
        return values[arg]
    if op == "Fn::Equals" and len(arg) == 2:
        return condition(arg[0], values) == condition(arg[1], values)
    if op == "Fn::And" and 2 <= len(arg) <= 10:
        return all(condition(a, values) for a in arg)
    raise ValueError("Unexpected region condition")


class StorageContract(unittest.TestCase):
    def test_no_auxiliary_resources_parameters_macros_or_secret_inputs(self):
        self.assertEqual(set(TEMPLATE), {"AWSTemplateFormatVersion", "Description", "Parameters", "Conditions", "Resources"})
        self.assertEqual(TEMPLATE["AWSTemplateFormatVersion"], "2010-09-09")
        self.assertEqual(set(TEMPLATE["Parameters"]), {"ProvisionStorage"})
        param = TEMPLATE["Parameters"]["ProvisionStorage"]
        self.assertEqual(param["Type"], "String")
        self.assertEqual(param["Default"], "disabled")
        self.assertEqual(set(param["AllowedValues"]), {"enabled", "disabled"})
        self.assertEqual(set(TEMPLATE["Resources"]), set(BUCKETS) | {b + "Policy" for b in BUCKETS})

    def test_resource_creation_matrix_and_retention(self):
        for region, mode in itertools.product(["eu-central-1", "us-east-1", "eu-west-1"], ["enabled", "disabled"]):
            values = {"AWS::Region": region, "ProvisionStorage": mode}
            for name, res in TEMPLATE["Resources"].items():
                with self.subTest(region=region, mode=mode, resource=name):
                    self.assertEqual(condition(TEMPLATE["Conditions"][res["Condition"]], values),
                                     region == "eu-central-1" and mode == "enabled")
                    self.assertEqual((res["DeletionPolicy"], res["UpdateReplacePolicy"]), ("Retain", "Retain"))

    def test_two_versioned_private_buckets_without_age_cleanup_or_website(self):
        for name, purpose in BUCKETS.items():
            with self.subTest(bucket=name):
                res = TEMPLATE["Resources"][name]
                self.assertEqual(res["Type"], "AWS::S3::Bucket")
                p = res["Properties"]
                self.assertEqual(set(p), {"VersioningConfiguration", "BucketEncryption", "OwnershipControls",
                                          "PublicAccessBlockConfiguration", "Tags"})
                self.assertEqual(p["VersioningConfiguration"], {"Status": "Enabled"})
                self.assertEqual(p["OwnershipControls"], {"Rules": [{"ObjectOwnership": "BucketOwnerEnforced"}]})
                self.assertEqual(p["BucketEncryption"], {"ServerSideEncryptionConfiguration": [
                    {"ServerSideEncryptionByDefault": {"SSEAlgorithm": "AES256"}}]})
                self.assertEqual(set(p["PublicAccessBlockConfiguration"]), PUBLIC_BLOCK)
                self.assertTrue(all(v is True for v in p["PublicAccessBlockConfiguration"].values()))
                self.assertEqual(p["Tags"], [{"Key": "MediaPurpose", "Value": purpose}])

    def verify_policy(self, res, bucket):
        self.assertEqual(res["Type"], "AWS::S3::BucketPolicy")
        p = res["Properties"]
        self.assertEqual(p["Bucket"], {"Ref": bucket})
        policy = p["PolicyDocument"]
        self.assertEqual(policy["Version"], "2012-10-17")
        statements = policy["Statement"]
        self.assertEqual(len(statements), 2)
        for s in statements:
            self.assertEqual((s["Principal"], s["Effect"]), ("*", "Deny"))
            self.assertCountEqual(s["Resource"], [{"Fn::GetAtt": [bucket, "Arn"]}, {"Fn::Sub": "${" + bucket + ".Arn}/*"}])
        tls, = (s for s in statements if s["Action"] == "s3:*")
        self.assertEqual(set(tls), {"Sid", "Effect", "Principal", "Action", "Resource", "Condition"})
        self.assertEqual(tls["Condition"], {"Bool": {"aws:SecureTransport": "false"}})
        retention, = (s for s in statements if s is not tls)
        self.assertEqual(set(retention), {"Sid", "Effect", "Principal", "Action", "Resource"})
        self.assertCountEqual(retention["Action"], NO_DELETE)

    def test_enforce_tls_and_protect_versions_on_each_own_bucket(self):
        for b in BUCKETS:
            with self.subTest(bucket=b):
                self.verify_policy(TEMPLATE["Resources"][b + "Policy"], b)

    def test_policy_regression_controls(self):
        # Change the actual candidate in memory. Each case must invalidate the policy.
        for b in BUCKETS:
            for fault in ["wrong bucket", "allows reads", "HTTPS denied", "missing object scope", "purge version"]:
                with self.subTest(bucket=b, fault=fault):
                    res = copy.deepcopy(TEMPLATE["Resources"][b + "Policy"])
                    rules = res["Properties"]["PolicyDocument"]["Statement"]
                    tls = next(s for s in rules if s["Action"] == "s3:*")
                    hold = next(s for s in rules if s is not tls)
                    if fault == "wrong bucket":
                        res["Properties"]["Bucket"]["Ref"] = next(other for other in BUCKETS if other != b)
                    elif fault == "allows reads":
                        rules.append({"Effect": "Allow", "Principal": "*", "Action": "s3:GetObject"})
                    elif fault == "HTTPS denied":
                        tls["Condition"]["Bool"]["aws:SecureTransport"] = "true"
                    elif fault == "missing object scope":
                        tls["Resource"] = [r for r in tls["Resource"] if "Fn::Sub" not in r]
                    else:
                        hold["Action"].remove("s3:DeleteObjectVersion")
                    with self.assertRaises(AssertionError):
                        self.verify_policy(res, b)


if __name__ == "__main__":
    unittest.main(verbosity=2)

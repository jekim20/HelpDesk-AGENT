# Candidate Validation

- Status: PASS
- Total: 186
- PASS: 165
- WARN_NEAR_DUPLICATE: 9
- FAIL: 0
- Machine validated: 174
- Machine rejected: 12
- Blocking failures: 0
- Pilot generated: 126
- Pilot approved: 52
- Production generated: 60
- Production approved: 11
- Production target: 1000
- Production remaining: 940
- Exact duplicates: 1
- Normalized duplicates: 0
- Near duplicates: 12
- Near-duplicate threshold: 0.9
- Approved training export: 11
- Training model input: 11
- Dry-run excluded from approval: 35
- External UNREVIEWED: 20
- Resource consistency groups: G06
- Generation subtype counts: {'multi_resource_howto': 10, 'multi_resource_write': 10, 'single_resource_howto': 10, 'single_resource_write': 10}

## Record results

| ID | Status | Details |
| --- | --- | --- |
| AUG_G01_001_0001 | PASS | - |
| AUG_G01_001_0002 | PASS | - |
| AUG_G01_001_0003 | PASS | - |
| AUG_G01_001_0004 | PASS | - |
| AUG_G01_001_0005 | PASS | - |
| AUG_G01_002_0001 | PASS | - |
| AUG_G01_002_0002 | PASS | - |
| AUG_G01_002_0003 | PASS | - |
| AUG_G01_002_0004 | PASS | - |
| AUG_G01_002_0005 | PASS | - |
| AUG_G06_001_0001 | PASS | - |
| AUG_G06_001_0002 | PASS | - |
| AUG_G06_001_0003 | PASS | - |
| AUG_G06_001_0004 | PASS | - |
| AUG_G06_001_0005 | PASS | - |
| AUG_G06_001_0006 | PASS | - |
| AUG_G06_001_0007 | PASS | - |
| AUG_G06_001_0008 | PASS | - |
| AUG_G06_001_0009 | PASS | - |
| AUG_G06_001_0010 | PASS | - |
| AUG_G06_003_0001 | PASS | - |
| AUG_G06_003_0002 | PASS | - |
| AUG_G06_003_0003 | PASS | - |
| AUG_G06_003_0004 | PASS | - |
| AUG_G06_003_0005 | PASS | - |
| AUG_G06_003_0006 | PASS | - |
| AUG_G06_003_0007 | PASS | - |
| AUG_G06_003_0008 | PASS | - |
| AUG_G06_003_0009 | PASS | - |
| AUG_G06_003_0010 | PASS | - |
| AUG_G06_004_0001 | PASS | - |
| AUG_G06_004_0002 | MACHINE_REJECTED | AUG_G06_004_0002: conversation resource DEV_DB does not match target resource PROD_DB |
| AUG_G06_004_0003 | PASS | - |
| AUG_G06_004_0004 | PASS | - |
| AUG_G06_004_0005 | PASS | - |
| AUG_G06_004_0006 | PASS | - |
| AUG_G06_004_0007 | PASS | - |
| AUG_G06_004_0008 | PASS | - |
| AUG_G06_004_0009 | PASS | - |
| AUG_G06_004_0010 | PASS | - |
| AUG_G06_005_0001 | PASS | - |
| AUG_G06_005_0002 | PASS | - |
| AUG_G06_005_0003 | PASS | - |
| AUG_G06_005_0004 | PASS | - |
| AUG_G06_005_0005 | PASS | - |
| AUG_G06_005_0006 | PASS | - |
| AUG_G06_005_0007 | PASS | - |
| AUG_G06_005_0008 | PASS | - |
| AUG_G06_005_0009 | PASS | - |
| AUG_G06_005_0010 | PASS | - |
| AUG_G10_001_0001 | PASS | - |
| AUG_G10_001_0002 | PASS | - |
| AUG_G10_001_0003 | PASS | - |
| AUG_G10_001_0004 | PASS | - |
| AUG_G10_001_0005 | PASS | - |
| AUG_G10_002_0001 | PASS | - |
| AUG_G10_002_0002 | PASS | - |
| AUG_G10_002_0003 | PASS | - |
| AUG_G10_002_0004 | PASS | - |
| AUG_G10_002_0005 | PASS | - |
| AUG_G10_003_0001 | PASS | - |
| AUG_G10_003_0002 | PASS | - |
| AUG_G10_003_0003 | PASS | - |
| AUG_G10_003_0004 | PASS | - |
| AUG_G10_003_0005 | PASS | - |
| AUG_G11_002_0001 | PASS | - |
| AUG_G11_002_0002 | PASS | - |
| AUG_G11_002_0003 | PASS | - |
| AUG_G11_002_0004 | WARN_NEAR_DUPLICATE | near duplicate with AUG_G11_100_0001: 0.9231 |
| AUG_G11_002_0005 | PASS | - |
| AUG_G11_100_0001 | WARN_NEAR_DUPLICATE | near duplicate with AUG_G11_002_0004: 0.9231 |
| AUG_G11_100_0002 | PASS | - |
| AUG_G11_100_0003 | PASS | - |
| AUG_G11_100_0004 | WARN_NEAR_DUPLICATE | near duplicate with AUG_G11_102_0002: 0.9091; high sibling similarity for S057 |
| AUG_G11_100_0005 | PASS | - |
| AUG_G11_100_0006 | PASS | - |
| AUG_G11_100_0007 | PASS | - |
| AUG_G11_100_0008 | PASS | - |
| AUG_G11_100_0009 | PASS | - |
| AUG_G11_100_0010 | PASS | - |
| AUG_G11_101_0001 | PASS | - |
| AUG_G11_101_0002 | PASS | - |
| AUG_G11_101_0003 | PASS | - |
| AUG_G11_101_0004 | PASS | - |
| AUG_G11_101_0005 | PASS | - |
| AUG_G11_101_0006 | PASS | - |
| AUG_G11_101_0007 | PASS | - |
| AUG_G11_101_0008 | PASS | - |
| AUG_G11_101_0009 | PASS | - |
| AUG_G11_101_0010 | PASS | - |
| AUG_G11_101_0011 | PASS | - |
| AUG_G11_101_0012 | PASS | - |
| AUG_G11_101_0013 | PASS | - |
| AUG_G11_101_0014 | PASS | - |
| AUG_G11_101_0015 | PASS | - |
| AUG_G11_102_0001 | PASS | - |
| AUG_G11_102_0002 | WARN_NEAR_DUPLICATE | near duplicate with AUG_G11_100_0004: 0.9091; high sibling similarity for S057 |
| AUG_G11_102_0003 | PASS | - |
| AUG_G11_102_0004 | PASS | - |
| AUG_G11_102_0005 | PASS | - |
| AUG_G11_102_0006 | PASS | - |
| AUG_G11_102_0007 | PASS | - |
| AUG_G11_102_0008 | PASS | - |
| AUG_G11_102_0009 | PASS | - |
| AUG_G11_102_0010 | PASS | - |
| AUG_G11_102_0011 | PASS | - |
| AUG_G11_102_0012 | PASS | - |
| AUG_G11_102_0013 | PASS | - |
| AUG_G11_102_0014 | PASS | - |
| AUG_G11_102_0015 | PASS | - |
| AUG_G12_001_0001 | PASS | - |
| AUG_G12_001_0002 | PASS | - |
| AUG_G12_001_0003 | PASS | - |
| AUG_G12_001_0004 | PASS | - |
| AUG_G12_001_0005 | PASS | - |
| AUG_G12_002_0001 | PASS | - |
| AUG_G12_002_0002 | PASS | - |
| AUG_G12_002_0003 | PASS | - |
| AUG_G12_002_0004 | PASS | - |
| AUG_G12_002_0005 | PASS | - |
| AUG_G15_001_0001 | PASS | - |
| AUG_G15_001_0002 | PASS | - |
| AUG_G15_001_0003 | PASS | - |
| AUG_G15_001_0004 | PASS | - |
| AUG_G15_001_0005 | PASS | - |
| AUG_G15_001_0006 | PASS | - |
| AUG_G15_001_0007 | PASS | - |
| AUG_G15_001_0008 | PASS | - |
| AUG_G15_001_0009 | PASS | - |
| AUG_G15_001_0010 | PASS | - |
| AUG_G15_002_0001 | WARN_NEAR_DUPLICATE | near duplicate with AUG_G15_003_0001: 0.9074; high sibling similarity for S043; near duplicate with AUG_G15_005_0001: 0.9174; near duplicate with AUG_G15_010_0001: 0.9174 |
| AUG_G15_002_0002 | PASS | - |
| AUG_G15_002_0003 | WARN_NEAR_DUPLICATE | near duplicate with AUG_G15_004_0002: 0.9135; high sibling similarity for S045; near duplicate with AUG_G15_006_0002: 0.9231 |
| AUG_G15_002_0004 | PASS | - |
| AUG_G15_002_0005 | PASS | - |
| AUG_G15_002_0006 | PASS | - |
| AUG_G15_002_0007 | WARN_NEAR_DUPLICATE | near duplicate with AUG_G15_003_0001: 0.9423; high sibling similarity for S043; near duplicate with AUG_G15_005_0001: 0.9524; near duplicate with AUG_G15_010_0001: 0.9524 |
| AUG_G15_002_0008 | PASS | - |
| AUG_G15_002_0009 | PASS | - |
| AUG_G15_002_0010 | PASS | - |
| AUG_G15_003_0001 | WARN_NEAR_DUPLICATE | near duplicate with AUG_G15_002_0001: 0.9074; high sibling similarity for S043; near duplicate with AUG_G15_002_0007: 0.9423; near duplicate with AUG_G15_005_0001: 0.9907; near duplicate with AUG_G15_010_0001: 0.9907 |
| AUG_G15_003_0002 | PASS | - |
| AUG_G15_004_0001 | PASS | - |
| AUG_G15_004_0002 | WARN_NEAR_DUPLICATE | near duplicate with AUG_G15_002_0003: 0.9135; high sibling similarity for S045 |
| AUG_G15_005_0001 | MACHINE_REJECTED | exact duplicate text with AUG_G15_010_0001; near duplicate with AUG_G15_002_0001: 0.9174; high sibling similarity for S043; near duplicate with AUG_G15_002_0007: 0.9524; near duplicate with AUG_G15_003_0001: 0.9907 |
| AUG_G15_005_0002 | PASS | - |
| AUG_G15_006_0001 | PASS | - |
| AUG_G15_006_0002 | MACHINE_REJECTED | AUG_G15_006_0002: single_resource_write requires exactly 1 supported resource, found ['DEV_DB', 'PROD_DB']; near duplicate with AUG_G15_002_0003: 0.9231; high sibling similarity for S045 |
| AUG_G15_007_0001 | MACHINE_REJECTED | AUG_G15_007_0001: multi_resource_write latest explicit resource DEV_DB does not match target resource PROD_DB |
| AUG_G15_007_0002 | PASS | - |
| AUG_G15_008_0001 | PASS | - |
| AUG_G15_008_0002 | PASS | - |
| AUG_G15_009_0001 | MACHINE_REJECTED | AUG_G15_009_0001: multi_resource_howto requires at least 5 conversation turns; AUG_G15_009_0001: multi_resource_howto first user turn must not list multiple supported resources; AUG_G15_009_0001: multi_resource_howto resources must appear sequentially in different user turns |
| AUG_G15_009_0002 | MACHINE_REJECTED | AUG_G15_009_0002: multi_resource_howto requires at least 5 conversation turns; AUG_G15_009_0002: multi_resource_howto first user turn must not list multiple supported resources; AUG_G15_009_0002: multi_resource_howto resources must appear sequentially in different user turns |
| AUG_G15_010_0001 | MACHINE_REJECTED | exact duplicate text with AUG_G15_005_0001; near duplicate with AUG_G15_002_0001: 0.9174; high sibling similarity for S043; near duplicate with AUG_G15_002_0007: 0.9524; near duplicate with AUG_G15_003_0001: 0.9907 |
| AUG_G15_010_0002 | PASS | - |
| AUG_G15_011_0001 | MACHINE_REJECTED | AUG_G15_011_0001: multi_resource_write latest explicit resource VPN does not match target resource PROD_DB |
| AUG_G15_011_0002 | MACHINE_REJECTED | AUG_G15_011_0002: multi_resource_write final user turn must not repeat a supported resource; AUG_G15_011_0002: multi_resource_write latest explicit resource DEV_DB does not match target resource PROD_DB |
| AUG_G15_012_0001 | PASS | - |
| AUG_G15_012_0002 | PASS | - |
| AUG_G15_013_0001 | MACHINE_REJECTED | AUG_G15_013_0001: multi_resource_howto requires at least 5 conversation turns; AUG_G15_013_0001: multi_resource_howto final user turn must not repeat a supported resource |
| AUG_G15_013_0002 | MACHINE_REJECTED | AUG_G15_013_0002: multi_resource_howto requires at least 5 conversation turns; AUG_G15_013_0002: multi_resource_howto final user turn must not repeat a supported resource |
| AUG_G15_018_0001 | PASS | - |
| AUG_G15_019_0001 | MACHINE_REJECTED | AUG_G15_019_0001: multi_resource_write final user turn must not repeat a supported resource |
| AUG_G15_020_0001 | PASS | - |
| AUG_G15_021_0001 | PASS | - |
| AUG_G15_100_0001 | PASS | - |
| AUG_G15_100_0002 | PASS | - |
| AUG_G15_100_0003 | PASS | - |
| AUG_G15_100_0004 | PASS | - |
| AUG_G15_100_0005 | PASS | - |
| AUG_G15_101_0001 | PASS | - |
| AUG_G15_101_0002 | PASS | - |
| AUG_G15_101_0003 | PASS | - |
| AUG_G15_101_0004 | PASS | - |
| AUG_G15_101_0005 | PASS | - |
| AUG_G15_102_0001 | PASS | - |
| AUG_G15_102_0002 | PASS | - |
| AUG_G15_102_0003 | PASS | - |
| AUG_G15_102_0004 | PASS | - |
| AUG_G15_102_0005 | PASS | - |
| AUG_G15_103_0001 | PASS | - |
| AUG_G15_103_0002 | PASS | - |
| AUG_G15_103_0003 | PASS | - |
| AUG_G15_103_0004 | PASS | - |
| AUG_G15_103_0005 | PASS | - |

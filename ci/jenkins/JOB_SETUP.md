# The `phishlens-ci` job

The pipeline itself is the root [`Jenkinsfile`](../../Jenkinsfile); this job only tells Jenkins where to find it. Start Jenkins first ([README.md](README.md)).

## Create the job

1. Jenkins → **New Item** → name `phishlens-ci` → **Pipeline** → **OK**.
2. **Pipeline** section:
   - **Definition:** Pipeline script from SCM
   - **SCM:** Git
   - **Repository URL:** `https://github.com/MUKUL-PRASAD-SIGH/PhishLens.git`. The repo is public, so no credentials are needed.
   - **Branch Specifier:** `*/main`

     > **Temporary: `*/ci/jenkinsfile-core` until the scan-api PRs merge.** `main` has neither `scan-api/` nor this Jenkinsfile until the stacked PRs (#2–#5 and the CI PRs) are merged, so a job on `*/main` has nothing to build. While they are open, the job points at `*/ci/jenkinsfile-core`, which has the whole stack, and the manual build and push-triggered build are proven there. Once everything is merged, set this back to `*/main`, push one commit to `main` and confirm it triggers a build.
   - **Script Path:** `Jenkinsfile`
   - **Lightweight checkout:** leave ticked
3. Leave **Build Triggers** empty. The trigger comes from `triggers { pollSCM('* * * * *') }` in the Jenkinsfile.
4. **Save.**

## Run the first build by hand

Click **Build Now** once.

Jenkins only learns about `pollSCM` when it reads the Jenkinsfile, and it only reads the Jenkinsfile when a build runs. Until that first build, the job has no trigger, so pushes are ignored. After the first build, **Configure → Build Triggers** shows **Poll SCM** ticked with `* * * * *`, filled in from the Jenkinsfile.

## Check that pushes trigger builds

1. Push a commit to `main`.
2. Within about a minute **Polling Log** (left menu of the job) shows `Changes found`, and a new build starts.
3. Its console begins with `Started by an SCM change`, and its name is `#<build> <short-sha>`.

## What a build produces

| Stage | Result |
|---|---|
| Test | JUnit results for scan-api (**Test Result** on the build page) |
| Model tests | pytest results for risk-model, in the same Test Result view. Skipped if `risk-model/tests` doesn't exist. |
| Package | `scan-api/target/scan-api.jar` |
| Archive | `scan-api.jar` and `scan-api-<short-sha>-b<build>.jar` under **Build Artifacts**, both fingerprinted |
| Docker build & push, Trivy scan | Placeholders, skipped until `scan-api/Dockerfile` exists |

## Checking a build from the command line

Jenkins' JSON API answers the same questions as the build page. Use an API token (user menu → **Security** → **API Token**), never the password, and keep it out of the repo. Queries with a `tree=` filter need `curl -g`, otherwise curl reads the `[ ]` as its own glob syntax and sends a broken request:

```bash
curl -gs -u "$JENKINS_USER:$JENKINS_TOKEN" \
  'http://localhost:8081/job/phishlens-ci/lastBuild/api/json?tree=displayName,result,artifacts[fileName]'
```

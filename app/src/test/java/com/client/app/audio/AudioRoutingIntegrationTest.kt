2026-09-26T19:28:43.9468312Z Current runner version: '2.337.0'
2026-09-26T19:28:43.9503646Z ##[group]Runner Image Provisioner
2026-09-26T19:28:43.9505009Z Hosted Compute Agent
2026-09-26T19:28:43.9506010Z Version: 20260828.587
2026-09-26T19:28:43.9507097Z Commit: abac92662cab4cc7352de4f9f9d2e2419aad9c29
2026-09-26T19:28:43.9508375Z Build Date: 2026-08-28T16:44:25Z
2026-09-26T19:28:43.9509810Z Worker ID: {974c3637-ea8d-40ed-9565-230f8c5bb2ce}
2026-09-26T19:28:43.9510974Z Azure Region: eastus2
2026-09-26T19:28:43.9512053Z ##[endgroup]
2026-09-26T19:28:43.9514826Z ##[group]Operating System
2026-09-26T19:28:43.9516493Z Ubuntu
2026-09-26T19:28:43.9517328Z 24.04.5
2026-09-26T19:28:43.9518116Z LTS
2026-09-26T19:28:43.9519160Z ##[endgroup]
2026-09-26T19:28:43.9520361Z ##[group]Runner Image
2026-09-26T19:28:43.9521381Z Image: ubuntu-24.04
2026-09-26T19:28:43.9522444Z Version: 20260920.314.1
2026-09-26T19:28:43.9524589Z Included Software: https://github.com/actions/runner-images/blob/ubuntu24/20260920.314/images/ubuntu/Ubuntu2404-Readme.md
2026-09-26T19:28:43.9527285Z Image Release: https://github.com/actions/runner-images/releases/tag/ubuntu24%2F20260920.314
2026-09-26T19:28:43.9529053Z ##[endgroup]
2026-09-26T19:28:43.9531554Z ##[group]GITHUB_TOKEN Permissions
2026-09-26T19:28:43.9534585Z Contents: read
2026-09-26T19:28:43.9535566Z Metadata: read
2026-09-26T19:28:43.9536551Z ##[endgroup]
2026-09-26T19:28:43.9539871Z Secret source: Actions
2026-09-26T19:28:43.9541505Z Cache mode: write
2026-09-26T19:28:43.9543141Z Prepare workflow directory
2026-09-26T19:28:44.0018755Z Prepare all required actions
2026-09-26T19:28:44.0090408Z Getting action download info
2026-09-26T19:28:44.2375297Z Download action repository 'actions/checkout@v4' (SHA:11d5960a326750d5838078e36cf38b85af677262)
2026-09-26T19:28:44.3534756Z Download action repository 'actions/setup-java@v4' (SHA:cf277c60eb25467037889841efdb72551f06f6c3)
2026-09-26T19:28:44.6194270Z Download action repository 'android-actions/setup-android@v4' (SHA:be39fa834029ff78f1a44aa3bb0819b8fc2bd8fd)
2026-09-26T19:28:44.7497804Z Download action repository 'gradle/actions@v4' (SHA:ed408507eac070d1f99cc633dbcf757c94c7933a)
2026-09-26T19:28:45.3541950Z Download action repository 'actions/upload-artifact@v4' (SHA:ea165f8d65b6e75b540449e92b4886f43607fa02)
2026-09-26T19:28:45.6282957Z Complete job name: build-and-test
2026-09-26T19:28:45.7371769Z ##[group]Run actions/checkout@v4
2026-09-26T19:28:45.7373199Z with:
2026-09-26T19:28:45.7373995Z   lfs: true
2026-09-26T19:28:45.7374879Z   repository: rdumbrovskyimail-eng/Client
2026-09-26T19:28:45.7384629Z   token: ***
2026-09-26T19:28:45.7385489Z   ssh-strict: true
2026-09-26T19:28:45.7386346Z   ssh-user: git
2026-09-26T19:28:45.7387217Z   persist-credentials: true
2026-09-26T19:28:45.7388178Z   clean: true
2026-09-26T19:28:45.7389070Z   sparse-checkout-cone-mode: true
2026-09-26T19:28:45.7390264Z   fetch-depth: 1
2026-09-26T19:28:45.7391115Z   fetch-tags: false
2026-09-26T19:28:45.7392034Z   show-progress: true
2026-09-26T19:28:45.7392957Z   submodules: false
2026-09-26T19:28:45.7393887Z   set-safe-directory: true
2026-09-26T19:28:45.7394899Z   allow-unsafe-pr-checkout: false
2026-09-26T19:28:45.7396231Z ##[endgroup]
2026-09-26T19:28:45.8454318Z Syncing repository: rdumbrovskyimail-eng/Client
2026-09-26T19:28:45.8458508Z ##[group]Getting Git version info
2026-09-26T19:28:45.8461007Z Working directory is '/home/runner/work/Client/Client'
2026-09-26T19:28:45.8464232Z [command]/usr/bin/git version
2026-09-26T19:28:45.8518654Z git version 2.55.0
2026-09-26T19:28:45.8575274Z [command]/usr/bin/git lfs version
2026-09-26T19:28:45.9313093Z git-lfs/3.8.0 (GitHub; linux amd64; go 1.27.0)
2026-09-26T19:28:45.9328408Z ##[endgroup]
2026-09-26T19:28:45.9345797Z Temporarily overriding HOME='/home/runner/work/_temp/1fa04a77-0394-40c4-a14f-6e515e105172' before making global git config changes
2026-09-26T19:28:45.9351242Z Adding repository directory to the temporary git global config as a safe directory
2026-09-26T19:28:45.9357967Z [command]/usr/bin/git config --global --add safe.directory /home/runner/work/Client/Client
2026-09-26T19:28:45.9421076Z Deleting the contents of '/home/runner/work/Client/Client'
2026-09-26T19:28:45.9426516Z ##[group]Initializing the repository
2026-09-26T19:28:45.9431660Z [command]/usr/bin/git init /home/runner/work/Client/Client
2026-09-26T19:28:45.9578649Z hint: Using 'master' as the name for the initial branch. This default branch name
2026-09-26T19:28:45.9582376Z hint: will change to "main" in Git 3.0. To configure the initial branch name
2026-09-26T19:28:45.9584704Z hint: to use in all of your new repositories, which will suppress this warning,
2026-09-26T19:28:45.9586205Z hint: call:
2026-09-26T19:28:45.9587006Z hint:
2026-09-26T19:28:45.9588017Z hint: 	git config --global init.defaultBranch <name>
2026-09-26T19:28:45.9589212Z hint:
2026-09-26T19:28:45.9590728Z hint: Names commonly chosen instead of 'master' are 'main', 'trunk' and
2026-09-26T19:28:45.9592612Z hint: 'development'. The just-created branch can be renamed via this command:
2026-09-26T19:28:45.9594342Z hint:
2026-09-26T19:28:45.9595752Z hint: 	git branch -m <name>
2026-09-26T19:28:45.9597405Z hint:
2026-09-26T19:28:45.9599921Z hint: Disable this message with "git config set advice.defaultBranchName false"
2026-09-26T19:28:45.9603416Z Initialized empty Git repository in /home/runner/work/Client/Client/.git/
2026-09-26T19:28:45.9608302Z [command]/usr/bin/git remote add origin https://github.com/rdumbrovskyimail-eng/Client
2026-09-26T19:28:45.9670784Z ##[endgroup]
2026-09-26T19:28:45.9673995Z ##[group]Disabling automatic garbage collection
2026-09-26T19:28:45.9676497Z [command]/usr/bin/git config --local gc.auto 0
2026-09-26T19:28:45.9710933Z ##[endgroup]
2026-09-26T19:28:45.9712407Z ##[group]Setting up auth
2026-09-26T19:28:45.9721734Z [command]/usr/bin/git config --local --name-only --get-regexp core\.sshCommand
2026-09-26T19:28:45.9770932Z [command]/usr/bin/git submodule foreach --recursive sh -c "git config --local --name-only --get-regexp 'core\.sshCommand' && git config --local --unset-all 'core.sshCommand' || :"
2026-09-26T19:28:46.0075325Z [command]/usr/bin/git config --local --name-only --get-regexp http\.https\:\/\/github\.com\/\.extraheader
2026-09-26T19:28:46.0117059Z [command]/usr/bin/git submodule foreach --recursive sh -c "git config --local --name-only --get-regexp 'http\.https\:\/\/github\.com\/\.extraheader' && git config --local --unset-all 'http.https://github.com/.extraheader' || :"
2026-09-26T19:28:46.0355122Z [command]/usr/bin/git config --local --name-only --get-regexp ^includeIf\.gitdir:
2026-09-26T19:28:46.0389864Z [command]/usr/bin/git submodule foreach --recursive git config --local --show-origin --name-only --get-regexp remote.origin.url
2026-09-26T19:28:46.0632788Z [command]/usr/bin/git config --local http.https://github.com/.extraheader AUTHORIZATION: basic ***
2026-09-26T19:28:46.0674703Z ##[endgroup]
2026-09-26T19:28:46.0678934Z [command]/usr/bin/git lfs install --local
2026-09-26T19:28:46.1081822Z Updated Git hooks.
2026-09-26T19:28:46.1084090Z Git LFS initialized.
2026-09-26T19:28:46.1114480Z ##[group]Fetching the repository
2026-09-26T19:28:46.1118298Z [command]/usr/bin/git -c protocol.version=2 fetch --no-tags --prune --no-recurse-submodules --depth=1 origin +0be23825eaed73155ace9db7030d6ba49600ec9e:refs/remotes/origin/main
2026-09-26T19:28:46.4184218Z From https://github.com/rdumbrovskyimail-eng/Client
2026-09-26T19:28:46.4185884Z  * [new ref]         0be23825eaed73155ace9db7030d6ba49600ec9e -> origin/main
2026-09-26T19:28:46.4200795Z ##[endgroup]
2026-09-26T19:28:46.4201654Z ##[group]Determining the checkout info
2026-09-26T19:28:46.4203836Z ##[endgroup]
2026-09-26T19:28:46.4204802Z ##[group]Fetching LFS objects
2026-09-26T19:28:46.4211234Z [command]/usr/bin/git lfs fetch origin refs/remotes/origin/main
2026-09-26T19:28:46.4451619Z Fetching reference refs/remotes/origin/main
2026-09-26T19:28:46.9322329Z ##[endgroup]
2026-09-26T19:28:46.9327504Z [command]/usr/bin/git sparse-checkout disable
2026-09-26T19:28:46.9389703Z [command]/usr/bin/git config --local --unset-all extensions.worktreeConfig
2026-09-26T19:28:46.9422456Z ##[group]Checking out the ref
2026-09-26T19:28:46.9426586Z [command]/usr/bin/git checkout --progress --force -B main refs/remotes/origin/main
2026-09-26T19:28:46.9827147Z Switched to a new branch 'main'
2026-09-26T19:28:46.9832558Z branch 'main' set up to track 'origin/main'.
2026-09-26T19:28:47.0113053Z ##[endgroup]
2026-09-26T19:28:47.0161037Z [command]/usr/bin/git log -1 --format=%H
2026-09-26T19:28:47.0187630Z 0be23825eaed73155ace9db7030d6ba49600ec9e
2026-09-26T19:28:47.0390745Z ##[group]Run set -euo pipefail
2026-09-26T19:28:47.0391192Z [36;1mset -euo pipefail[0m
2026-09-26T19:28:47.0391614Z [36;1mMODEL_PATH="app/src/main/assets/models/silero_vad_v5_quant.onnx"[0m
2026-09-26T19:28:47.0392230Z [36;1mtest -f "$MODEL_PATH" || { echo "Missing Silero V5 model in repository"; exit 1; }[0m
2026-09-26T19:28:47.0392760Z [36;1mgit lfs pull --include="$MODEL_PATH"[0m
2026-09-26T19:28:47.0393125Z [36;1mgit lfs checkout -- "$MODEL_PATH"[0m
2026-09-26T19:28:47.0393470Z [36;1mtest -s "$MODEL_PATH"[0m
2026-09-26T19:28:47.0393786Z [36;1mSIZE_BYTES="$(stat -c%s "$MODEL_PATH")"[0m
2026-09-26T19:28:47.0394309Z [36;1mtest "$SIZE_BYTES" -ge 500000 || { echo "Silero V5 model is too small: $SIZE_BYTES"; exit 1; }[0m
2026-09-26T19:28:47.0394909Z [36;1mif head -c 128 "$MODEL_PATH" | grep -aq "git-lfs.github.com/spec"; then[0m
2026-09-26T19:28:47.0395402Z [36;1m  echo "Silero V5 model is still an LFS pointer"; exit 1[0m
2026-09-26T19:28:47.0395804Z [36;1mfi[0m
2026-09-26T19:28:47.0396220Z [36;1mecho "Silero V5 model SHA-256 (must be reviewed against the pinned repository artifact):"[0m
2026-09-26T19:28:47.0396740Z [36;1msha256sum "$MODEL_PATH"[0m
2026-09-26T19:28:47.0715043Z shell: /usr/bin/bash --noprofile --norc -e -o pipefail {0}
2026-09-26T19:28:47.0715564Z ##[endgroup]
2026-09-26T19:28:47.1412957Z Silero V5 model SHA-256 (must be reviewed against the pinned repository artifact):
2026-09-26T19:28:47.1451881Z 1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3  app/src/main/assets/models/silero_vad_v5_quant.onnx
2026-09-26T19:28:47.1587967Z ##[group]Run actions/setup-java@v4
2026-09-26T19:28:47.1588331Z with:
2026-09-26T19:28:47.1588576Z   java-version: 17
2026-09-26T19:28:47.1588843Z   distribution: temurin
2026-09-26T19:28:47.1589113Z   java-package: jdk
2026-09-26T19:28:47.1589665Z   check-latest: false
2026-09-26T19:28:47.1589956Z   server-id: github
2026-09-26T19:28:47.1590236Z   server-username: GITHUB_ACTOR
2026-09-26T19:28:47.1590547Z   server-password: GITHUB_TOKEN
2026-09-26T19:28:47.1590852Z   overwrite-settings: true
2026-09-26T19:28:47.1591132Z   job-status: success
2026-09-26T19:28:47.1593890Z   token: ***
2026-09-26T19:28:47.1594142Z ##[endgroup]
2026-09-26T19:28:47.3267072Z ##[warning]setup-java v4 is deprecated and will no longer receive updates. Please migrate to actions/setup-java@v5.
2026-09-26T19:28:47.3279992Z ##[group]Installed distributions
2026-09-26T19:28:47.3319942Z Resolved Java 17.0.20+1 from tool-cache
2026-09-26T19:28:47.3320636Z Setting Java 17.0.20+1 as the default
2026-09-26T19:28:47.3331586Z (node:2433) [DEP0040] DeprecationWarning: The `punycode` module is deprecated. Please use a userland alternative instead.
2026-09-26T19:28:47.3332437Z (Use `node --trace-deprecation ...` to show where the warning was created)
2026-09-26T19:28:47.3335375Z Creating toolchains.xml for JDK version 17 from temurin
2026-09-26T19:28:47.3413402Z Writing to /home/runner/.m2/toolchains.xml
2026-09-26T19:28:47.3413879Z 
2026-09-26T19:28:47.3414097Z Java configuration:
2026-09-26T19:28:47.3414621Z   Distribution: temurin
2026-09-26T19:28:47.3415015Z   Version: 17.0.20+1
2026-09-26T19:28:47.3415544Z   Path: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:28:47.3415933Z 
2026-09-26T19:28:47.3416333Z ##[endgroup]
2026-09-26T19:28:47.3433902Z Creating settings.xml with server-id: github
2026-09-26T19:28:47.3463963Z Writing to /home/runner/.m2/settings.xml
2026-09-26T19:28:47.3653594Z ##[group]Run android-actions/setup-android@v4
2026-09-26T19:28:47.3654171Z with:
2026-09-26T19:28:47.3654432Z   packages: platform-tools
2026-09-26T19:28:47.3654745Z   cmdline-tools-version: 15859902
2026-09-26T19:28:47.3655086Z   accept-android-sdk-licenses: true
2026-09-26T19:28:47.3655436Z   log-accepted-android-sdk-licenses: true
2026-09-26T19:28:47.3655761Z env:
2026-09-26T19:28:47.3656113Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:28:47.3656667Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:28:47.3657099Z ##[endgroup]
2026-09-26T19:28:47.4542568Z Found preinstalled sdkmanager in /usr/local/lib/android/sdk/cmdline-tools/latest with following source.properties:
2026-09-26T19:28:47.4543653Z Pkg.Revision=12.0
2026-09-26T19:28:47.4544200Z Pkg.Path=cmdline-tools;12.0
2026-09-26T19:28:47.4544572Z Pkg.Desc=Android SDK Command-line Tools
2026-09-26T19:28:47.4544820Z 
2026-09-26T19:28:47.4545037Z Wrong version in preinstalled sdkmanager
2026-09-26T19:28:47.4545807Z Downloading commandline tools from https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
2026-09-26T19:28:48.2348757Z [command]/usr/bin/unzip -o -q /home/runner/work/_temp/ad4bd0d6-ed2d-40d2-ab2b-894a46a5d23b
2026-09-26T19:28:49.0763605Z Accepting Android SDK licenses
2026-09-26T19:28:49.0767585Z [command]/usr/local/lib/android/sdk/cmdline-tools/22.0/bin/sdkmanager --licenses
2026-09-26T19:28:49.3001215Z WARNING: The SDK Manager CLI tool (sdkmanager) is deprecated. Use Android CLI instead.
2026-09-26T19:28:49.3002575Z The 'android' binary can also be found in the cmdline-tools directory, and 'android sdk' is the replacement for 'sdkmanager'.
2026-09-26T19:28:49.3004469Z To learn more about the Android CLI and how to use it, see the documentation (https://d.android.com/tools/agents/android-cli)
2026-09-26T19:28:49.3022732Z 
2026-09-26T19:28:50.7020939Z Loading local repository...                                                     
2026-09-26T19:28:50.7021796Z [=========                              ] 25% Loading local repository...       
2026-09-26T19:28:51.2031120Z [=========                              ] 25% Fetch remote repository...        
2026-09-26T19:28:51.5530941Z [==========                             ] 26% Fetch remote repository...        
2026-09-26T19:28:51.5537479Z [============                           ] 31% Fetch remote repository...        
2026-09-26T19:28:51.6042293Z [============                           ] 32% Fetch remote repository...        
2026-09-26T19:28:51.6049719Z [=============                          ] 33% Fetch remote repository...        
2026-09-26T19:28:51.6337180Z [=============                          ] 35% Fetch remote repository...        
2026-09-26T19:28:51.6338443Z [==============                         ] 36% Fetch remote repository...        
2026-09-26T19:28:51.6588541Z [==============                         ] 37% Fetch remote repository...        
2026-09-26T19:28:51.6596872Z [===============                        ] 38% Fetch remote repository...        
2026-09-26T19:28:51.6802433Z [===============                        ] 39% Fetch remote repository...        
2026-09-26T19:28:51.6810196Z [================                       ] 40% Fetch remote repository...        
2026-09-26T19:28:51.6998814Z [================                       ] 41% Fetch remote repository...        
2026-09-26T19:28:51.7000044Z [================                       ] 42% Fetch remote repository...        
2026-09-26T19:28:51.7183497Z [=================                      ] 44% Fetch remote repository...        
2026-09-26T19:28:51.7184592Z [=================                      ] 45% Fetch remote repository...        
2026-09-26T19:28:51.7412377Z [==================                     ] 46% Fetch remote repository...        
2026-09-26T19:28:51.7416771Z [==================                     ] 47% Fetch remote repository...        
2026-09-26T19:28:51.7694088Z [===================                    ] 48% Fetch remote repository...        
2026-09-26T19:28:51.7695775Z [===================                    ] 49% Fetch remote repository...        
2026-09-26T19:28:51.7919182Z [====================                   ] 50% Fetch remote repository...        
2026-09-26T19:28:51.7941245Z [====================                   ] 51% Fetch remote repository...        
2026-09-26T19:28:51.8162942Z [====================                   ] 53% Fetch remote repository...        
2026-09-26T19:28:51.8190049Z [=====================                  ] 54% Fetch remote repository...        
2026-09-26T19:28:51.8395863Z [=====================                  ] 55% Fetch remote repository...        
2026-09-26T19:28:51.8420166Z [======================                 ] 56% Fetch remote repository...        
2026-09-26T19:28:51.8576156Z [======================                 ] 57% Fetch remote repository...        
2026-09-26T19:28:51.8590069Z [=======================                ] 58% Fetch remote repository...        
2026-09-26T19:28:51.9003737Z [=======================                ] 59% Fetch remote repository...        
2026-09-26T19:28:51.9005121Z [========================               ] 60% Fetch remote repository...        
2026-09-26T19:28:51.9287163Z [========================               ] 61% Fetch remote repository...        
2026-09-26T19:28:51.9288577Z [=========================              ] 63% Fetch remote repository...        
2026-09-26T19:28:51.9476535Z [=========================              ] 64% Fetch remote repository...        
2026-09-26T19:28:51.9477589Z [=========================              ] 65% Fetch remote repository...        
2026-09-26T19:28:51.9660198Z [==========================             ] 66% Fetch remote repository...        
2026-09-26T19:28:51.9661250Z [==========================             ] 67% Fetch remote repository...        
2026-09-26T19:28:52.0080399Z [===========================            ] 68% Fetch remote repository...        
2026-09-26T19:28:52.0090325Z [===========================            ] 69% Fetch remote repository...        
2026-09-26T19:28:52.1060412Z [============================           ] 70% Fetch remote repository...        
2026-09-26T19:28:52.1061116Z [============================           ] 72% Fetch remote repository...        
2026-09-26T19:28:52.1788861Z [=============================          ] 73% Fetch remote repository...        
2026-09-26T19:28:52.1790266Z [=============================          ] 74% Fetch remote repository...        
2026-09-26T19:28:52.1910187Z [=============================          ] 75% Fetch remote repository...        
2026-09-26T19:28:52.2040249Z [=============================          ] 75% Computing updates...              
2026-09-26T19:28:52.2070204Z [=======================================] 100% Computing updates...             
2026-09-26T19:28:52.2431423Z 6 of 7 SDK package licenses not accepted.
2026-09-26T19:28:52.2432323Z Review licenses that have not been accepted (y/N)? 
2026-09-26T19:28:52.2433227Z 1/6: License android-googletv-license:
2026-09-26T19:28:52.2434111Z ---------------------------------------
2026-09-26T19:28:52.2434850Z Terms and Conditions
2026-09-26T19:28:52.2435286Z 
2026-09-26T19:28:52.2435892Z This is the Google TV Add-on for the Android Software Development Kit License Agreement.
2026-09-26T19:28:52.2436693Z 
2026-09-26T19:28:52.2437023Z 1. Introduction
2026-09-26T19:28:52.2437415Z 
2026-09-26T19:28:52.2440400Z 1.1 The Google TV Add-on for the Android Software Development Kit (referred to in this License Agreement as the "Google TV Add-on" and specifically including the Android system files, packaged APIs, and Google APIs add-ons) is licensed to you subject to the terms of this License Agreement. This License Agreement forms a legally binding contract between you and Google in relation to your use of the Google TV Add-on.
2026-09-26T19:28:52.2448253Z 
2026-09-26T19:28:52.2449998Z 1.2 "Google" means Google Inc., a Delaware corporation with principal place of business at 1600 Amphitheatre Parkway, Mountain View, CA 94043, United States.
2026-09-26T19:28:52.2451425Z 
2026-09-26T19:28:52.2451800Z 2. Accepting this License Agreement
2026-09-26T19:28:52.2452301Z 
2026-09-26T19:28:52.2453560Z 2.1 In order to use the Google TV Add-on, you must first agree to this License Agreement. You may not use the Google TV Add-on if you do not accept this License Agreement.
2026-09-26T19:28:52.2454781Z 
2026-09-26T19:28:52.2455186Z 2.2 You can accept this License Agreement by:
2026-09-26T19:28:52.2461806Z 
2026-09-26T19:28:52.2462595Z (A) clicking to accept or agree to this License Agreement, where this option is made available to you; or
2026-09-26T19:28:52.2463492Z 
2026-09-26T19:28:52.2464565Z (B) by actually using the Google TV Add-on. In this case, you agree that use of the Google TV Add-on constitutes acceptance of the License Agreement from that point onwards.
2026-09-26T19:28:52.2465813Z 
2026-09-26T19:28:52.2467567Z 2.3 You may not use the Google TV Add-on and may not accept the Licensing Agreement if you are a person barred from receiving the Google TV Add-on under the laws of the United States or other countries including the country in which you are resident or from which you use the Google TV Add-on.
2026-09-26T19:28:52.2469725Z 
2026-09-26T19:28:52.2472069Z 2.4 If you are agreeing to be bound by this License Agreement on behalf of your employer or other entity, you represent and warrant that you have full legal authority to bind your employer or such entity to this License Agreement. If you do not have the requisite authority, you may not accept the Licensing Agreement or use the Google TV Add-on on behalf of your employer or other entity.
2026-09-26T19:28:52.2474629Z 
2026-09-26T19:28:52.2475032Z 3. Google TV Add-on License from Google
2026-09-26T19:28:52.2475561Z 
2026-09-26T19:28:52.2477042Z 3.1 Subject to the terms of this License Agreement, Google grants you a limited, worldwide, royalty-free, non- assignable and non-exclusive license to use the Google TV Add-on solely to develop applications to run on the Google TV platform.
2026-09-26T19:28:52.2478681Z 
2026-09-26T19:28:52.2481217Z 3.2 You agree that Google or third parties own all legal right, title and interest in and to the Google TV Add-on, including any Intellectual Property Rights that subsist in the Google TV Add-on. "Intellectual Property Rights" means any and all rights under patent law, copyright law, trade secret law, trademark law, and any and all other proprietary rights. Google reserves all rights not expressly granted to you.
2026-09-26T19:28:52.2485504Z 
2026-09-26T19:28:52.2489222Z 3.3 Except to the extent required by applicable third party licenses, you may not copy (except for backup purposes), modify, adapt, redistribute, decompile, reverse engineer, disassemble, or create derivative works of the Google TV Add-on or any part of the Google TV Add-on. Except to the extent required by applicable third party licenses, you may not load any part of the Google TV Add-on onto a mobile handset, television, or any other hardware device except a personal computer, combine any part of the Google TV Add-on with other software, or distribute any software or device incorporating a part of the Google TV Add-on.
2026-09-26T19:28:52.2493357Z 
2026-09-26T19:28:52.2494844Z 3.4 Use, reproduction and distribution of components of the Google TV Add-on licensed under an open source software license are governed solely by the terms of that open source software license and not this License Agreement.
2026-09-26T19:28:52.2496419Z 
2026-09-26T19:28:52.2499807Z 3.5 You agree that the form and nature of the Google TV Add-on that Google provides may change without prior notice to you and that future versions of the Google TV Add-on may be incompatible with applications developed on previous versions of the Google TV Add-on. You agree that Google may stop (permanently or temporarily) providing the Google TV Add-on (or any features within the Google TV Add-on) to you or to users generally at Google's sole discretion, without prior notice to you.
2026-09-26T19:28:52.2502963Z 
2026-09-26T19:28:52.2505290Z 3.6 Nothing in this License Agreement gives you a right to use any of Google's or it’s licensors’ trade names, trademarks, service marks, logos, domain names, or other distinctive brand features.
2026-09-26T19:28:52.2506856Z 
2026-09-26T19:28:52.2508387Z 3.7 You agree that you will not remove, obscure, or alter any proprietary rights notices (including copyright and trademark notices) that may be affixed to or contained within the Google TV Add-on.
2026-09-26T19:28:52.2510062Z 
2026-09-26T19:28:52.2510442Z 4. Use of the Google TV Add-on by You
2026-09-26T19:28:52.2510956Z 
2026-09-26T19:28:52.2512671Z 4.1 Google agrees that it obtains no right, title or interest from you (or your licensors) under this License Agreement in or to any software applications that you develop using the Google TV Add-on, including any intellectual property rights that subsist in those applications.
2026-09-26T19:28:52.2514581Z 
2026-09-26T19:28:52.2516862Z 4.2 You agree to use the Google TV Add-on and write applications only for purposes that are permitted by (a) this License Agreement and (b) any applicable law, regulation or generally accepted practices or guidelines in the relevant jurisdictions (including any laws regarding the export of data or software to and from the United States or other relevant countries).
2026-09-26T19:28:52.2519242Z 
2026-09-26T19:28:52.2524110Z 4.3 You agree that if you use the Google TV Add-on to develop applications for general public users, you will protect the privacy and legal rights of those users. If the users provide you with user names, passwords, or other login information or personal information, your must make the users aware that the information will be available to your application, and you must provide legally adequate privacy notice and protection for those users. If your application stores personal or sensitive information provided by users, it must do so securely. If the user provides your application with Google Account information, your application may only use that information to access the user's Google Account when, and for the limited purposes for which, the user has given you explicit permission to do so.
2026-09-26T19:28:52.2528912Z 
2026-09-26T19:28:52.2531514Z 4.4 You agree that you will not engage in any activity with the Google TV Add-on, including the development or distribution of an application, that interferes with, disrupts, damages, or accesses in an unauthorized manner the servers, networks, or other properties or services of any third party including, but not limited to, Google, Multichannel Video Program Distributors or any mobile communications carrier.
2026-09-26T19:28:52.2534158Z 
2026-09-26T19:28:52.2536416Z 4.5 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any data, content, or resources that you create, transmit or display through the Google TV platform and/or applications for the Google TV platform, and for the consequences of your actions (including any loss or damage which Google may suffer) by doing so.
2026-09-26T19:28:52.2538884Z 
2026-09-26T19:28:52.2541531Z 4.6 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any breach of your obligations under this License Agreement, any applicable third party contract or Terms of Service, or any applicable law or regulation, and for the consequences (including any loss or damage which Google or any third party may suffer) of any such breach.
2026-09-26T19:28:52.2544142Z 
2026-09-26T19:28:52.2544521Z 5. Your Developer Credentials
2026-09-26T19:28:52.2545017Z 
2026-09-26T19:28:52.2546806Z 5.1 You agree that you are responsible for maintaining the confidentiality of any developer credentials that may be issued to you by Google or which you may choose yourself and that you will be solely responsible for all applications that are developed under your developer credentials.
2026-09-26T19:28:52.2548789Z 
2026-09-26T19:28:52.2549587Z 6. Privacy and Information
2026-09-26T19:28:52.2550123Z 
2026-09-26T19:28:52.2553465Z 6.1 In order to continually innovate and improve the Google TV Add-on, Google may collect certain usage statistics from the software including but not limited to a unique identifier, associated IP address, version number of the software, and information on which tools and/or services in the Google TV Add-on are being used and how they are being used. Before any of this information is collected, the Google TV Add-on will notify you and seek your consent. If you withhold consent, the information will not be collected.
2026-09-26T19:28:52.2556756Z 
2026-09-26T19:28:52.2557752Z 6.2 The data collected is examined in the aggregate to improve the Google TV Add-on and is maintained in accordance with Google's Privacy Policy.
2026-09-26T19:28:52.2558925Z 
2026-09-26T19:28:52.2559703Z 7. Third Party Applications for the Google TV Platform
2026-09-26T19:28:52.2560361Z 
2026-09-26T19:28:52.2563950Z 7.1 If you use the Google TV Add-on to run applications developed by a third party or that access data, content or resources provided by a third party, you agree that Google is not responsible for those applications, data, content, or resources. You understand that all data, content or resources which you may access through such third party applications are the sole responsibility of the person from which they originated and that Google is not liable for any loss or damage that you may experience as a result of the use or access of any of those third party applications, data, content, or resources.
2026-09-26T19:28:52.2567658Z 
2026-09-26T19:28:52.2570778Z 7.2 You should be aware the data, content, and resources presented to you through such a third party application may be protected by intellectual property rights which are owned by the providers (or by other persons or companies on their behalf). You may not modify, rent, lease, loan, sell, distribute or create derivative works based on these data, content, or resources (either in whole or in part) unless you have been specifically given permission to do so by the relevant owners.
2026-09-26T19:28:52.2573789Z 
2026-09-26T19:28:52.2575429Z 7.3 You acknowledge that your use of such third party applications, data, content, or resources may be subject to separate terms between you and the relevant third party. In that case, this License Agreement does not affect your legal relationship with these third parties.
2026-09-26T19:28:52.2577252Z 
2026-09-26T19:28:52.2577626Z 8. Using Google TV APIs
2026-09-26T19:28:52.2578073Z 
2026-09-26T19:28:52.2582974Z 8.1 If you use any Google TV API to retrieve data from Google, you acknowledge that the data (“Google TV API Content”) may be protected by intellectual property rights which are owned by Google or those parties that provide the data (or by other persons or companies on their behalf). Your use of any such API may be subject to additional Terms of Service. You may not modify, rent, lease, loan, sell, distribute or create derivative works based on this data (either in whole or in part) unless allowed by the relevant Terms of Service. Some portions of the Google TV API Content are licensed to Google by third parties, including but not limited to Tribune Media Services
2026-09-26T19:28:52.2587093Z 
2026-09-26T19:28:52.2588594Z 8.2 If you use any API to retrieve a user's data from Google, you acknowledge and agree that you shall retrieve data only with the user's explicit consent and only when, and for the limited purposes for which, the user has given you permission to do so.
2026-09-26T19:28:52.2590751Z 
2026-09-26T19:28:52.2591515Z 8.3 Except as explicitly permitted in Section 3 (Google TV Add-on License from Google), you must:
2026-09-26T19:28:52.2592389Z 
2026-09-26T19:28:52.2593524Z (a) not modify nor format the Google TV API Content except to the extent reasonably and technically necessary to optimize the display such Google TV API Content in your application;
2026-09-26T19:28:52.2594983Z 
2026-09-26T19:28:52.2596536Z (b) not edit the Google TV API Content in a manner that renders the Google TV API Content inaccurate of alters its inherent meaning (provided that displaying excerpts will not violate the foregoing); or
2026-09-26T19:28:52.2598209Z 
2026-09-26T19:28:52.2598919Z (c) not create any commercial audience measurement tool or service using the Google TV API Content
2026-09-26T19:28:52.2600097Z 
2026-09-26T19:28:52.2600468Z 9. Terminating this License Agreement
2026-09-26T19:28:52.2600985Z 
2026-09-26T19:28:52.2601724Z 9.1 This License Agreement will continue to apply until terminated by either you or Google as set out below.
2026-09-26T19:28:52.2602645Z 
2026-09-26T19:28:52.2603622Z 9.2 If you want to terminate this License Agreement, you may do so by ceasing your use of the Google TV Add-on and any relevant developer credentials.
2026-09-26T19:28:52.2604763Z 
2026-09-26T19:28:52.2605276Z 9.3 Google may at any time, terminate this License Agreement with you if:
2026-09-26T19:28:52.2605963Z 
2026-09-26T19:28:52.2606451Z (A) you have breached any provision of this License Agreement; or
2026-09-26T19:28:52.2607153Z 
2026-09-26T19:28:52.2607560Z (B) Google is required to do so by law; or
2026-09-26T19:28:52.2608076Z 
2026-09-26T19:28:52.2609515Z (C) the partner with whom Google offered certain parts of Google TV Add-on (such as APIs) to you has terminated its relationship with Google or ceased to offer certain parts of the Google TV Add-on to you; or
2026-09-26T19:28:52.2610994Z 
2026-09-26T19:28:52.2613060Z (D) Google decides to no longer providing the Google TV Add-on or certain parts of the Google TV Add-on to users in the country in which you are resident or from which you use the service, or the provision of the Google TV Add-on or certain Google TV Add-on services to you by Google is, in Google's sole discretion, no longer commercially viable.
2026-09-26T19:28:52.2615303Z 
2026-09-26T19:28:52.2618094Z 9.4 When this License Agreement comes to an end, all of the legal rights, obligations and liabilities that you and Google have benefited from, been subject to (or which have accrued over time whilst this License Agreement has been in force) or which are expressed to continue indefinitely, shall be unaffected by this cessation, and the provisions of paragraph 14.7 shall continue to apply to such rights, obligations and liabilities indefinitely.
2026-09-26T19:28:52.2621167Z 
2026-09-26T19:28:52.2621576Z 10. DISCLAIMER OF WARRANTIES
2026-09-26T19:28:52.2622215Z 
2026-09-26T19:28:52.2623494Z 10.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT YOUR USE OF THE GOOGLE TV ADD-ON IS AT YOUR SOLE RISK AND THAT THE GOOGLE TV ADD-ON IS PROVIDED "AS IS" AND "AS AVAILABLE" WITHOUT WARRANTY OF ANY KIND FROM GOOGLE.
2026-09-26T19:28:52.2624923Z 
2026-09-26T19:28:52.2626685Z 10.2 YOUR USE OF THE GOOGLE TV ADD-ON AND ANY MATERIAL DOWNLOADED OR OTHERWISE OBTAINED THROUGH THE USE OF THE GOOGLE TV ADD-ON IS AT YOUR OWN DISCRETION AND RISK AND YOU ARE SOLELY RESPONSIBLE FOR ANY DAMAGE TO YOUR COMPUTER SYSTEM OR OTHER DEVICE OR LOSS OF DATA THAT RESULTS FROM SUCH USE.
2026-09-26T19:28:52.2628623Z 
2026-09-26T19:28:52.2630430Z 10.3 GOOGLE FURTHER EXPRESSLY DISCLAIMS ALL WARRANTIES AND CONDITIONS OF ANY KIND, WHETHER EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE IMPLIED WARRANTIES AND CONDITIONS OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT.
2026-09-26T19:28:52.2632232Z 
2026-09-26T19:28:52.2632587Z 11. LIMITATION OF LIABILITY
2026-09-26T19:28:52.2633037Z 
2026-09-26T19:28:52.2635627Z 11.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT GOOGLE, ITS SUBSIDIARIES AND AFFILIATES, AND ITS LICENSORS SHALL NOT BE LIABLE TO YOU UNDER ANY THEORY OF LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL CONSEQUENTIAL OR EXEMPLARY DAMAGES THAT MAY BE INCURRED BY YOU, INCLUDING ANY LOSS OF DATA, WHETHER OR NOT GOOGLE OR ITS REPRESENTATIVES HAVE BEEN ADVISED OF OR SHOULD HAVE BEEN AWARE OF THE POSSIBILITY OF ANY SUCH LOSSES ARISING.
2026-09-26T19:28:52.2638389Z 
2026-09-26T19:28:52.2638740Z 12. Indemnification
2026-09-26T19:28:52.2639518Z 
2026-09-26T19:28:52.2644045Z 12.1 To the maximum extent permitted by law, you agree to defend, indemnify and hold harmless Google, its affiliates and their respective directors, officers, employees and agents from and against any and all claims, actions, suits or proceedings, as well as any and all losses, liabilities, damages, costs and expenses (including reasonable attorneys fees) arising out of or accruing from (a) your use of the Google TV Add-on, (b) any application you develop on the Google TV Add-on that infringes any copyright, trademark, trade secret, trade dress, patent or other intellectual property right of any person or defames any person or violates their rights of publicity or privacy, and (c) any non-compliance by you with this License Agreement.
2026-09-26T19:28:52.2648748Z 
2026-09-26T19:28:52.2649118Z 13. Changes to the License Agreement
2026-09-26T19:28:52.2649849Z 
2026-09-26T19:28:52.2650597Z 13.1 Google may make changes to the License Agreement as it distributes new versions of the Google TV Add-on.
2026-09-26T19:28:52.2651539Z 
2026-09-26T19:28:52.2651889Z 14. General Legal Terms
2026-09-26T19:28:52.2652324Z 
2026-09-26T19:28:52.2654303Z 14.1 This License Agreement constitute the whole legal agreement between you and Google and govern your use of the Google TV Add-on (excluding any services which Google may provide to you under a separate written agreement), and completely replace any prior agreements between you and Google in relation to the Google TV Add-on.
2026-09-26T19:28:52.2656609Z 
2026-09-26T19:28:52.2658638Z 14.2 You agree that if Google does not exercise or enforce any legal right or remedy which is contained in this License Agreement (or which Google has the benefit of under any applicable law), this will not be taken to be a formal waiver of Google's rights and that those rights or remedies will still be available to Google.
2026-09-26T19:28:52.2661210Z 
2026-09-26T19:28:52.2663428Z 14.3 If any court of law, having the jurisdiction to decide on this matter, rules that any provision of this License Agreement is invalid, then that provision will be removed from this License Agreement without affecting the rest of this License Agreement. The remaining provisions of this License Agreement will continue to be valid and enforceable.
2026-09-26T19:28:52.2666029Z 
2026-09-26T19:28:52.2669768Z 14.4 You acknowledge and agree that Google’s API data licensors and each member of the group of companies of which Google is the parent shall be third party beneficiaries to this License Agreement and that such other companies shall be entitled to directly enforce, and rely upon, any provision of this License Agreement that confers a benefit on (or rights in favor of) them. Other than this, no other person or company shall be third party beneficiaries to this License Agreement.
2026-09-26T19:28:52.2672875Z 
2026-09-26T19:28:52.2674905Z 14.5 EXPORT RESTRICTIONS. THE GOOGLE TV ADD-ON IS SUBJECT TO UNITED STATES EXPORT LAWS AND REGULATIONS. YOU MUST COMPLY WITH ALL DOMESTIC AND INTERNATIONAL EXPORT LAWS AND REGULATIONS THAT APPLY TO THE GOOGLE TV ADD-ON. THESE LAWS INCLUDE RESTRICTIONS ON DESTINATIONS, END USERS AND END USE.
2026-09-26T19:28:52.2677317Z 
2026-09-26T19:28:52.2679620Z 14.6 The rights granted in this License Agreement may not be assigned or transferred by either you or Google without the prior written approval of the other party. Neither you nor Google shall be permitted to delegate their responsibilities or obligations under this License Agreement without the prior written approval of the other party.
2026-09-26T19:28:52.2681874Z 
2026-09-26T19:28:52.2685417Z 14.7 This License Agreement, and your relationship with Google under this License Agreement, shall be governed by the laws of the State of California without regard to its conflict of laws provisions. You and Google agree to submit to the exclusive jurisdiction of the courts located within the county of Santa Clara, California to resolve any legal matter arising from this License Agreement. Notwithstanding this, you agree that Google shall still be allowed to apply for injunctive remedies (or an equivalent type of urgent legal relief) in any jurisdiction.
2026-09-26T19:28:52.2689236Z 
2026-09-26T19:28:52.2689241Z 
2026-09-26T19:28:52.2689537Z August 15, 2011
2026-09-26T19:28:52.2690026Z ---------------------------------------
2026-09-26T19:28:52.2690477Z Accept? (y/N): 
2026-09-26T19:28:52.2690749Z 2/6: License android-googlexr-license:
2026-09-26T19:28:52.2691043Z ---------------------------------------
2026-09-26T19:28:52.2692029Z To get started with the Android XR Emulator System Image SDK, you must agree to the following terms and conditions. As described below, please note that this is a preview, emulated version of the Android XR OS, subject to change, that you use at your own risk.
2026-09-26T19:28:52.2692912Z 
2026-09-26T19:28:52.2693079Z This is the Android XR Emulator System Image SDK License Agreement
2026-09-26T19:28:52.2693345Z 
2026-09-26T19:28:52.2693423Z 1. Introduction
2026-09-26T19:28:52.2693544Z 
2026-09-26T19:28:52.2694952Z 1.1 The Android Android XR Emulator System Image SDK (referred to in the License Agreement as the "SDK" and specifically including the Android system files,, packaged APIs, library files (if and when they are made available), and Google applications and APIs add-ons) is licensed to you subject to the terms of the License Agreement. The License Agreement forms a legally binding contract between you and Google in relation to your use of the SDK.
2026-09-26T19:28:52.2696429Z 
2026-09-26T19:28:52.2697330Z 1.2 "Android" means the Android software stack for devices, as made available under the Android Open Source Project, which is located at the following URL: https://source.android.com/, as updated from time to time.
2026-09-26T19:28:52.2698070Z 
2026-09-26T19:28:52.2698707Z 1.3 "Google" means Google LLC, organized under the laws of the State of Delaware, USA, and operating under the laws of the USA with principal place of business at 1600 Amphitheatre Parkway, Mountain View, CA 94043, USA.
2026-09-26T19:28:52.2699728Z 
2026-09-26T19:28:52.2699843Z 2. Accepting this License Agreement
2026-09-26T19:28:52.2700041Z 
2026-09-26T19:28:52.2700450Z 2.1 In order to use the SDK, you must first agree to the License Agreement. You may not use the SDK if you do not accept the License Agreement.
2026-09-26T19:28:52.2700934Z 
2026-09-26T19:28:52.2701216Z 2.2 By clicking to accept and/or using this SDK, you hereby agree to the terms of the License Agreement.
2026-09-26T19:28:52.2701580Z 
2026-09-26T19:28:52.2702331Z 2.3 You may not use the SDK and may not accept the License Agreement if you are a person barred from receiving the SDK under the laws of the United States or other countries, including the country in which you are resident or from which you use the SDK.
2026-09-26T19:28:52.2703164Z 
2026-09-26T19:28:52.2704291Z 2.4 If you are agreeing to be bound by the License Agreement on behalf of your employer or other entity, you represent and warrant that you have full legal authority to bind your employer or such entity to the License Agreement. If you do not have the requisite authority, you may not accept the License Agreement or use the SDK on behalf of your employer or other entity.
2026-09-26T19:28:52.2705512Z 
2026-09-26T19:28:52.2705605Z 3. SDK License from Google
2026-09-26T19:28:52.2705749Z 
2026-09-26T19:28:52.2706458Z 3.1 Subject to the terms of the License Agreement, Google grants you a limited, worldwide, royalty-free, non-assignable, non-exclusive, and non-sublicensable license to use the SDK solely to develop applications for Android XR.
2026-09-26T19:28:52.2707250Z 
2026-09-26T19:28:52.2707901Z 3.2 You may not use this SDK to develop applications for other platforms or to develop another SDK. You are of course free to develop applications for other platforms provided that this SDK is not used for that purpose.
2026-09-26T19:28:52.2708645Z 
2026-09-26T19:28:52.2710293Z 3.3 You agree that Google or third parties own all legal right, title and interest in and to the SDK, including any Intellectual Property Rights that subsist in the SDK. "Intellectual Property Rights" means any and all rights under patent law, copyright law, trade secret law, trademark law, and any and all other proprietary rights. Google reserves all rights not expressly granted to you.
2026-09-26T19:28:52.2711711Z 
2026-09-26T19:28:52.2713507Z 3.4 You may not use the SDK for any purpose not expressly permitted by the License Agreement. Except to the extent required by applicable third party licenses, you may not (a) copy (except for backup purposes), modify, adapt, redistribute, decompile, reverse engineer, disassemble, or create derivative works of the SDK or any part of the SDK; or (b) load any part of the SDK onto a mobile handset or any other hardware device except a personal computer, combine any part of the SDK with other software, or distribute any software or device incorporating a part of the SDK.
2026-09-26T19:28:52.2715377Z 
2026-09-26T19:28:52.2716679Z 3.5 Use, reproduction and distribution of components of the SDK licensed under an open source software license are governed solely by the terms of that open source software license and not the License Agreement. You agree to remain a licensee in good standing in regard to such open source software licenses under all the rights granted and to refrain from any actions that may terminate, suspend, or breach such rights.
2026-09-26T19:28:52.2718077Z 
2026-09-26T19:28:52.2719561Z 3.6 You agree that the form and nature of the SDK that Google provides may change without prior notice to you and that future versions of the SDK may be incompatible with applications developed on previous versions of the SDK. You agree that Google may stop (permanently or temporarily) providing the SDK (or any features within the SDK) to you or to users generally at Google's sole discretion, without prior notice to you.
2026-09-26T19:28:52.2720966Z 
2026-09-26T19:28:52.2721475Z 3.7 Nothing in the License Agreement gives you a right to use any of Google's trade names, trademarks, service marks, logos, domain names, or other distinctive brand features.
2026-09-26T19:28:52.2722077Z 
2026-09-26T19:28:52.2722622Z 3.8 You agree that you will not remove, obscure, or alter any proprietary rights notices (including copyright and trademark notices) that may be affixed to or contained within the SDK.
2026-09-26T19:28:52.2723263Z 
2026-09-26T19:28:52.2723352Z 4. Use of the SDK by You
2026-09-26T19:28:52.2723489Z 
2026-09-26T19:28:52.2724292Z 4.1 Google agrees that it obtains no right, title or interest from you (or your licensors) under the License Agreement in or to any software applications that you develop using the SDK, including any intellectual property rights that subsist in those applications.
2026-09-26T19:28:52.2725184Z 
2026-09-26T19:28:52.2726283Z 4.2 You agree to use the SDK and write applications only for purposes that are permitted by (a) the License Agreement and (b) any applicable law, regulation or generally accepted practices or guidelines in the relevant jurisdictions (including any laws regarding the export of data or software to and from the United States or other relevant countries).
2026-09-26T19:28:52.2727481Z 
2026-09-26T19:28:52.2730228Z 4.3 You agree that if you use the SDK to develop applications for general public users, you will protect the privacy and legal rights of those users. If the users provide you with user names, passwords, or other login information or personal information, you must make the users aware that the information will be available to your application, and you must provide legally adequate privacy notice and protection for those users. If your application stores personal or sensitive information provided by users, it must do so securely. If the user provides your application with Google Account information, your application may only use that information to access the user's Google Account when, and for the limited purposes for which, the user has given you permission to do so.
2026-09-26T19:28:52.2732820Z 
2026-09-26T19:28:52.2734055Z 4.4 You agree that you will not engage in any activity with the SDK, including the development or distribution of an application, that interferes with, disrupts, damages, or accesses in an unauthorized manner the servers, networks, or other properties or services of any third party including, but not limited to, Google or any mobile communications carrier.
2026-09-26T19:28:52.2735352Z 
2026-09-26T19:28:52.2736419Z 4.5 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any data, content, or resources that you create, transmit or display through Android and/or applications for Android, and for the consequences of your actions (including any loss or damage which Google may suffer) by doing so.
2026-09-26T19:28:52.2737565Z 
2026-09-26T19:28:52.2738784Z 4.6 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any breach of your obligations under the License Agreement, any applicable third party contract or Terms of Service, or any applicable law or regulation, and for the consequences (including any loss or damage which Google or any third party may suffer) of any such breach.
2026-09-26T19:28:52.2740512Z 
2026-09-26T19:28:52.2741427Z 4.7 The SDK is in development, and your testing and feedback are an important part of the development process. By using the SDK, you acknowledge that implementation of some features are still under development and that you should not rely on the SDK having the full functionality of a stable release.
2026-09-26T19:28:52.2742442Z 
2026-09-26T19:28:52.2742545Z 5. Your Developer Credentials
2026-09-26T19:28:52.2742698Z 
2026-09-26T19:28:52.2743573Z 5.1 You agree that you are responsible for maintaining the confidentiality of any developer credentials that may be issued to you by Google or which you may choose yourself and that you will be solely responsible for all applications that are developed under your developer credentials.
2026-09-26T19:28:52.2744536Z 
2026-09-26T19:28:52.2744625Z 6. Privacy and Information
2026-09-26T19:28:52.2744773Z 
2026-09-26T19:28:52.2746271Z 6.1 In order to continually innovate and improve the SDK, Google may collect certain usage statistics from the software including but not limited to a unique identifier, associated IP address, version number of the software, and information on which tools and/or services in the SDK are being used and how they are being used. Before any of this information is collected, the SDK will notify you and seek your consent. If you withhold consent, the information will not be collected.
2026-09-26T19:28:52.2747859Z 
2026-09-26T19:28:52.2748485Z 6.2 The data collected is examined in the aggregate to improve the SDK and is maintained in accordance with Google's Privacy Policy, which is located at the following URL: https://policies.google.com/privacy
2026-09-26T19:28:52.2749233Z 
2026-09-26T19:28:52.2749712Z 6.3 Anonymized and aggregated sets of the data may be shared with Google partners to improve the SDK.
2026-09-26T19:28:52.2750117Z 
2026-09-26T19:28:52.2750256Z 7. Third Party Applications
2026-09-26T19:28:52.2750418Z 
2026-09-26T19:28:52.2752284Z 7.1 If you use the SDK to run applications developed by a third party or that access data, content or resources provided by a third party, you agree that Google is not responsible for those applications, data, content, or resources. You understand that all data, content or resources which you may access through such third party applications are the sole responsibility of the person from which they originated and that Google is not liable for any loss or damage that you may experience as a result of the use or access of any of those third party applications, data, content, or resources.
2026-09-26T19:28:52.2754209Z 
2026-09-26T19:28:52.2755880Z 7.2 You should be aware that the data, content, and resources presented to you through such a third party application may be protected by intellectual property rights which are owned by the providers (or by other persons or companies on their behalf). You may not modify, rent, lease, loan, sell, distribute or create derivative works based on these data, content, or resources (either in whole or in part) unless you have been specifically given permission to do so by the relevant owners.
2026-09-26T19:28:52.2757593Z 
2026-09-26T19:28:52.2758469Z 7.3 You acknowledge that your use of such third party applications, data, content, or resources may be subject to separate terms between you and the relevant third party. In that case, the License Agreement does not affect your legal relationship with these third parties.
2026-09-26T19:28:52.2759585Z 
2026-09-26T19:28:52.2759681Z 8. Using Android APIs
2026-09-26T19:28:52.2759817Z 
2026-09-26T19:28:52.2759905Z 8.1 Google Data APIs
2026-09-26T19:28:52.2760027Z 
2026-09-26T19:28:52.2761581Z 8.1.1 If you use any API to retrieve data from Google, you acknowledge that the data may be protected by intellectual property rights which are owned by Google or those parties that provide the data (or by other persons or companies on their behalf). Your use of any such API may be subject to additional Terms of Service. You may not modify, rent, lease, loan, sell, distribute or create derivative works based on this data (either in whole or in part) unless allowed by the relevant Terms of Service.
2026-09-26T19:28:52.2763215Z 
2026-09-26T19:28:52.2765884Z 8.1.2 If you use any API to retrieve a user's data from Google, you acknowledge and agree that you shall retrieve data only with the user's explicit consent and only when, and for the limited purposes for which, the user has given you permission to do so. If you use the Android Recognition Service API, documented at the following URL: https://developer.android.com/reference/android/speech/RecognitionService, as updated from time to time, you acknowledge that the use of the API is subject to the Data Processing Addendum for Products where Google is a Data Processor, which is located at the following URL: https://privacy.google.com/businesses/gdprprocessorterms/, as updated from time to time. By clicking to accept, you hereby agree to the terms of the Data Processing Addendum for Products where Google is a Data Processor.
2026-09-26T19:28:52.2768627Z 
2026-09-26T19:28:52.2768731Z 9. Terminating this License Agreement
2026-09-26T19:28:52.2768918Z 
2026-09-26T19:28:52.2769220Z 9.1 The License Agreement will continue to apply until terminated by either you or Google as set out below.
2026-09-26T19:28:52.2769790Z 
2026-09-26T19:28:52.2770167Z 9.2 If you want to terminate the License Agreement, you may do so by ceasing your use of the SDK and any relevant developer credentials.
2026-09-26T19:28:52.2770631Z 
2026-09-26T19:28:52.2772617Z 9.3 Google may at any time, terminate the License Agreement with you if: (A) you have breached any provision of the License Agreement; or (B) Google is required to do so by law; or (C) the partner with whom Google offered certain parts of SDK (such as APIs) to you has terminated its relationship with Google or ceased to offer certain parts of the SDK to you; or (D) Google decides to no longer provide the SDK or certain parts of the SDK to users in the country in which you are resident or from which you use the service, or the provision of the SDK or certain SDK services to you by Google is, in Google's sole discretion, no longer commercially viable.
2026-09-26T19:28:52.2774679Z 
2026-09-26T19:28:52.2776068Z 9.4 When the License Agreement comes to an end, all of the legal rights, obligations and liabilities that you and Google have benefited from, been subject to (or which have accrued over time whilst the License Agreement has been in force) or which are expressed to continue indefinitely, shall be unaffected by this cessation, and the provisions of paragraph 14.7 shall continue to apply to such rights, obligations and liabilities indefinitely.
2026-09-26T19:28:52.2777521Z 
2026-09-26T19:28:52.2777618Z 10. DISCLAIMER OF WARRANTIES
2026-09-26T19:28:52.2777769Z 
2026-09-26T19:28:52.2778406Z 10.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT YOUR USE OF THE SDK IS AT YOUR SOLE RISK AND THAT THE SDK IS PROVIDED "AS IS" AND "AS AVAILABLE" WITHOUT WARRANTY OF ANY KIND FROM GOOGLE.
2026-09-26T19:28:52.2779018Z 
2026-09-26T19:28:52.2780158Z 10.2 YOUR USE OF THE SDK AND ANY MATERIAL DOWNLOADED OR OTHERWISE OBTAINED THROUGH THE USE OF THE SDK IS AT YOUR OWN DISCRETION AND RISK AND YOU ARE SOLELY RESPONSIBLE FOR ANY DAMAGE TO YOUR COMPUTER SYSTEM OR OTHER DEVICE OR LOSS OF DATA THAT RESULTS FROM SUCH USE.
2026-09-26T19:28:52.2781053Z 
2026-09-26T19:28:52.2781845Z 10.3 GOOGLE FURTHER EXPRESSLY DISCLAIMS ALL WARRANTIES AND CONDITIONS OF ANY KIND, WHETHER EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE IMPLIED WARRANTIES AND CONDITIONS OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT.
2026-09-26T19:28:52.2782925Z 
2026-09-26T19:28:52.2783029Z 11. LIMITATION OF LIABILITY
2026-09-26T19:28:52.2783175Z 
2026-09-26T19:28:52.2784559Z 11.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT GOOGLE, ITS SUBSIDIARIES AND AFFILIATES, AND ITS LICENSORS SHALL NOT BE LIABLE TO YOU UNDER ANY THEORY OF LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL OR EXEMPLARY DAMAGES THAT MAY BE INCURRED BY YOU, INCLUDING ANY LOSS OF DATA, WHETHER OR NOT GOOGLE OR ITS REPRESENTATIVES HAVE BEEN ADVISED OF OR SHOULD HAVE BEEN AWARE OF THE POSSIBILITY OF ANY SUCH LOSSES ARISING.
2026-09-26T19:28:52.2786014Z 
2026-09-26T19:28:52.2786095Z 12. Indemnification
2026-09-26T19:28:52.2786221Z 
2026-09-26T19:28:52.2788496Z 12.1 To the maximum extent permitted by law, you agree to defend, indemnify and hold harmless Google, its affiliates and their respective directors, officers, employees and agents from and against any and all claims, actions, suits or proceedings, as well as any and all losses, liabilities, damages, costs and expenses (including reasonable attorneys fees) arising out of or accruing from (a) your use of the SDK, (b) any application you develop on the SDK that infringes any copyright, trademark, trade secret, trade dress, patent or other intellectual property right of any person or defames any person or violates their rights of publicity or privacy, and (c) any non-compliance by you with the License Agreement.
2026-09-26T19:28:52.2791043Z 
2026-09-26T19:28:52.2791144Z 13. Changes to the License Agreement
2026-09-26T19:28:52.2791322Z 
2026-09-26T19:28:52.2792028Z 13.1 Google may make changes to the License Agreement as it distributes new versions of the SDK. When these changes are made, Google will make a new version of the License Agreement available on the website where the SDK is made available.
2026-09-26T19:28:52.2793023Z 
2026-09-26T19:28:52.2793161Z 14. General Legal Terms
2026-09-26T19:28:52.2793978Z 
2026-09-26T19:28:52.2795253Z 14.1 The License Agreement constitutes the whole legal agreement between you and Google and governs your use of the SDK (excluding any services which Google may provide to you under a separate written agreement), and completely replaces any prior agreements between you and Google in relation to the SDK.
2026-09-26T19:28:52.2796308Z 
2026-09-26T19:28:52.2797294Z 14.2 You agree that if Google does not exercise or enforce any legal right or remedy which is contained in the License Agreement (or which Google has the benefit of under any applicable law), this will not be taken to be a formal waiver of Google's rights and that those rights or remedies will still be available to Google.
2026-09-26T19:28:52.2798361Z 
2026-09-26T19:28:52.2799633Z 14.3 If any court of law, having the jurisdiction to decide on this matter, rules that any provision of the License Agreement is invalid, then that provision will be removed from the License Agreement without affecting the rest of the License Agreement. The remaining provisions of the License Agreement will continue to be valid and enforceable.
2026-09-26T19:28:52.2800812Z 
2026-09-26T19:28:52.2802379Z 14.4 You acknowledge and agree that each member of the group of companies of which Google is the parent shall be third party beneficiaries to the License Agreement and that such other companies shall be entitled to directly enforce, and rely upon, any provision of the License Agreement that confers a benefit on (or rights in favor of) them. Other than this, no other person or company shall be third party beneficiaries to the License Agreement.
2026-09-26T19:28:52.2803960Z 
2026-09-26T19:28:52.2804775Z 14.5 EXPORT RESTRICTIONS. THE SDK IS SUBJECT TO UNITED STATES EXPORT LAWS AND REGULATIONS. YOU MUST COMPLY WITH ALL DOMESTIC AND INTERNATIONAL EXPORT LAWS AND REGULATIONS THAT APPLY TO THE SDK. THESE LAWS INCLUDE RESTRICTIONS ON DESTINATIONS, END USERS AND END USE.
2026-09-26T19:28:52.2805681Z 
2026-09-26T19:28:52.2806743Z 14.6 The rights granted in the License Agreement may not be assigned or transferred by either you or Google without the prior written approval of the other party. Neither you nor Google shall be permitted to delegate their responsibilities or obligations under the License Agreement without the prior written approval of the other party.
2026-09-26T19:28:52.2807882Z 
2026-09-26T19:28:52.2809842Z 14.7 The License Agreement, and your relationship with Google under the License Agreement, shall be governed by the laws of the State of California without regard to its conflict of laws provisions. You and Google agree to submit to the exclusive jurisdiction of the courts located within the county of Santa Clara, California to resolve any legal matter arising from the License Agreement. Notwithstanding this, you agree that Google shall still be allowed to apply for injunctive remedies (or an equivalent type of urgent legal relief) in any jurisdiction.
2026-09-26T19:28:52.2811795Z ---------------------------------------
2026-09-26T19:28:52.2812054Z Accept? (y/N): 
2026-09-26T19:28:52.2812281Z 3/6: License android-sdk-arm-dbt-license:
2026-09-26T19:28:52.2812555Z ---------------------------------------
2026-09-26T19:28:52.2812797Z Terms and Conditions
2026-09-26T19:28:52.2812924Z 
2026-09-26T19:28:52.2813085Z This is the Android Software Development Kit License Agreement
2026-09-26T19:28:52.2813336Z 
2026-09-26T19:28:52.2813415Z 1. Introduction
2026-09-26T19:28:52.2813531Z 
2026-09-26T19:28:52.2814655Z 1.1 The Android Software Development Kit (referred to in the License Agreement as the "SDK" and specifically including the Android system files, packaged APIs, and Google APIs add-ons) is licensed to you subject to the terms of the License Agreement. The License Agreement forms a legally binding contract between you and Google in relation to your use of the SDK.
2026-09-26T19:28:52.2815868Z 
2026-09-26T19:28:52.2816515Z 1.2 "Android" means the Android software stack for devices, as made available under the Android Open Source Project, which is located at the following URL: http://source.android.com/, as updated from time to time.
2026-09-26T19:28:52.2817240Z 
2026-09-26T19:28:52.2818325Z 1.3 A "compatible implementation" means any Android device that (i) complies with the Android Compatibility Definition document, which can be found at the Android compatibility website (http://source.android.com/compatibility) and which may be updated from time to time; and (ii) successfully passes the Android Compatibility Test Suite (CTS).
2026-09-26T19:28:52.2830061Z 
2026-09-26T19:28:52.2830930Z 1.4 "Google" means Google Inc., a Delaware corporation with principal place of business at 1600 Amphitheatre Parkway, Mountain View, CA 94043, United States.
2026-09-26T19:28:52.2831967Z 
2026-09-26T19:28:52.2831974Z 
2026-09-26T19:28:52.2832158Z 2. Accepting the License Agreement
2026-09-26T19:28:52.2832447Z 
2026-09-26T19:28:52.2833126Z 2.1 In order to use the SDK, you must first agree to the License Agreement. You may not use the SDK if you do not accept the License Agreement.
2026-09-26T19:28:52.2833964Z 
2026-09-26T19:28:52.2834326Z 2.2 By clicking to accept, you hereby agree to the terms of the License Agreement.
2026-09-26T19:28:52.2834858Z 
2026-09-26T19:28:52.2836368Z 2.3 You may not use the SDK and may not accept the License Agreement if you are a person barred from receiving the SDK under the laws of the United States or other countries, including the country in which you are resident or from which you use the SDK.
2026-09-26T19:28:52.2837866Z 
2026-09-26T19:28:52.2840068Z 2.4 If you are agreeing to be bound by the License Agreement on behalf of your employer or other entity, you represent and warrant that you have full legal authority to bind your employer or such entity to the License Agreement. If you do not have the requisite authority, you may not accept the License Agreement or use the SDK on behalf of your employer or other entity.
2026-09-26T19:28:52.2842377Z 
2026-09-26T19:28:52.2842383Z 
2026-09-26T19:28:52.2842543Z 3. SDK License from Google
2026-09-26T19:28:52.2842788Z 
2026-09-26T19:28:52.2844205Z 3.1 Subject to the terms of the License Agreement, Google grants you a limited, worldwide, royalty-free, non-assignable, non-exclusive, and non-sublicensable license to use the SDK solely to develop applications for compatible implementations of Android.
2026-09-26T19:28:52.2845778Z 
2026-09-26T19:28:52.2847641Z 3.2 You may not use this SDK to develop applications for other platforms (including non-compatible implementations of Android) or to develop another SDK. You are of course free to develop applications for other platforms, including non-compatible implementations of Android, provided that this SDK is not used for that purpose.
2026-09-26T19:28:52.2849829Z 
2026-09-26T19:28:52.2852018Z 3.3 You agree that Google or third parties own all legal right, title and interest in and to the SDK, including any Intellectual Property Rights that subsist in the SDK. "Intellectual Property Rights" means any and all rights under patent law, copyright law, trade secret law, trademark law, and any and all other proprietary rights. Google reserves all rights not expressly granted to you.
2026-09-26T19:28:52.2854329Z 
2026-09-26T19:28:52.2856211Z 3.4 You may not use the SDK for any purpose not expressly permitted by the License Agreement. Except to the extent required by applicable third party licenses, you may not copy (except for backup purposes), modify, adapt, redistribute, decompile, reverse engineer, disassemble, or create derivative works of the SDK or any part of the SDK.
2026-09-26T19:28:52.2858255Z 
2026-09-26T19:28:52.2859602Z 3.5 Use, reproduction and distribution of components of the SDK licensed under an open source software license are governed solely by the terms of that open source software license and not the License Agreement.
2026-09-26T19:28:52.2860894Z 
2026-09-26T19:28:52.2863231Z 3.6 You agree that the form and nature of the SDK that Google provides may change without prior notice to you and that future versions of the SDK may be incompatible with applications developed on previous versions of the SDK. You agree that Google may stop (permanently or temporarily) providing the SDK (or any features within the SDK) to you or to users generally at Google's sole discretion, without prior notice to you.
2026-09-26T19:28:52.2865612Z 
2026-09-26T19:28:52.2866498Z 3.7 Nothing in the License Agreement gives you a right to use any of Google's trade names, trademarks, service marks, logos, domain names, or other distinctive brand features.
2026-09-26T19:28:52.2867573Z 
2026-09-26T19:28:52.2868524Z 3.8 You agree that you will not remove, obscure, or alter any proprietary rights notices (including copyright and trademark notices) that may be affixed to or contained within the SDK.
2026-09-26T19:28:52.2869819Z 
2026-09-26T19:28:52.2869826Z 
2026-09-26T19:28:52.2869979Z 4. Use of the SDK by You
2026-09-26T19:28:52.2870212Z 
2026-09-26T19:28:52.2871635Z 4.1 Google agrees that it obtains no right, title or interest from you (or your licensors) under the License Agreement in or to any software applications that you develop using the SDK, including any intellectual property rights that subsist in those applications.
2026-09-26T19:28:52.2873244Z 
2026-09-26T19:28:52.2875356Z 4.2 You agree to use the SDK and write applications only for purposes that are permitted by (a) the License Agreement and (b) any applicable law, regulation or generally accepted practices or guidelines in the relevant jurisdictions (including any laws regarding the export of data or software to and from the United States or other relevant countries).
2026-09-26T19:28:52.2877550Z 
2026-09-26T19:28:52.2882260Z 4.3 You agree that if you use the SDK to develop applications for general public users, you will protect the privacy and legal rights of those users. If the users provide you with user names, passwords, or other login information or personal information, you must make the users aware that the information will be available to your application, and you must provide legally adequate privacy notice and protection for those users. If your application stores personal or sensitive information provided by users, it must do so securely. If the user provides your application with Google Account information, your application may only use that information to access the user's Google Account when, and for the limited purposes for which, the user has given you permission to do so.
2026-09-26T19:28:52.2886865Z 
2026-09-26T19:28:52.2888862Z 4.4 You agree that you will not engage in any activity with the SDK, including the development or distribution of an application, that interferes with, disrupts, damages, or accesses in an unauthorized manner the servers, networks, or other properties or services of any third party including, but not limited to, Google or any mobile communications carrier.
2026-09-26T19:28:52.2891183Z 
2026-09-26T19:28:52.2893100Z 4.5 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any data, content, or resources that you create, transmit or display through Android and/or applications for Android, and for the consequences of your actions (including any loss or damage which Google may suffer) by doing so.
2026-09-26T19:28:52.2895157Z 
2026-09-26T19:28:52.2897356Z 4.6 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any breach of your obligations under the License Agreement, any applicable third party contract or Terms of Service, or any applicable law or regulation, and for the consequences (including any loss or damage which Google or any third party may suffer) of any such breach.
2026-09-26T19:28:52.2899845Z 
2026-09-26T19:28:52.2902451Z 4.7 This software enables the execution of intellectual property owned by Arm Limited. You agree that your use of the software, that allows execution of ARM Instruction Set Architecture (“ISA”) compliant executables for application development and debug only on x86 desktop, laptop, customer on-premise servers, and customer-procured cloud-based environments.
2026-09-26T19:28:52.2904709Z 
2026-09-26T19:28:52.2904877Z 5. Your Developer Credentials
2026-09-26T19:28:52.2905145Z 
2026-09-26T19:28:52.2906733Z 5.1 You agree that you are responsible for maintaining the confidentiality of any developer credentials that may be issued to you by Google or which you may choose yourself and that you will be solely responsible for all applications that are developed under your developer credentials.
2026-09-26T19:28:52.2908520Z 
2026-09-26T19:28:52.2908670Z 6. Privacy and Information
2026-09-26T19:28:52.2908932Z 
2026-09-26T19:28:52.2911793Z 6.1 In order to continually innovate and improve the SDK, Google may collect certain usage statistics from the software including but not limited to a unique identifier, associated IP address, version number of the software, and information on which tools and/or services in the SDK are being used and how they are being used. Before any of this information is collected, the SDK will notify you and seek your consent. If you withhold consent, the information will not be collected.
2026-09-26T19:28:52.2914657Z 
2026-09-26T19:28:52.2915326Z 6.2 The data collected is examined in the aggregate to improve the SDK and is maintained in accordance with Google's Privacy Policy.
2026-09-26T19:28:52.2916149Z 
2026-09-26T19:28:52.2916156Z 
2026-09-26T19:28:52.2916309Z 7. Third Party Applications
2026-09-26T19:28:52.2916567Z 
2026-09-26T19:28:52.2920269Z 7.1 If you use the SDK to run applications developed by a third party or that access data, content or resources provided by a third party, you agree that Google is not responsible for those applications, data, content, or resources. You understand that all data, content or resources which you may access through such third party applications are the sole responsibility of the person from which they originated and that Google is not liable for any loss or damage that you may experience as a result of the use or access of any of those third party applications, data, content, or resources.
2026-09-26T19:28:52.2923851Z 
2026-09-26T19:28:52.2926592Z 7.2 You should be aware the data, content, and resources presented to you through such a third party application may be protected by intellectual property rights which are owned by the providers (or by other persons or companies on their behalf). You may not modify, rent, lease, loan, sell, distribute or create derivative works based on these data, content, or resources (either in whole or in part) unless you have been specifically given permission to do so by the relevant owners.
2026-09-26T19:28:52.2929585Z 
2026-09-26T19:28:52.2930970Z 7.3 You acknowledge that your use of such third party applications, data, content, or resources may be subject to separate terms between you and the relevant third party. In that case, the License Agreement does not affect your legal relationship with these third parties.
2026-09-26T19:28:52.2932582Z 
2026-09-26T19:28:52.2932588Z 
2026-09-26T19:28:52.2932720Z 8. Using Android APIs
2026-09-26T19:28:52.2932919Z 
2026-09-26T19:28:52.2933044Z 8.1 Google Data APIs
2026-09-26T19:28:52.2933280Z 
2026-09-26T19:28:52.2935758Z 8.1.1 If you use any API to retrieve data from Google, you acknowledge that the data may be protected by intellectual property rights which are owned by Google or those parties that provide the data (or by other persons or companies on their behalf). Your use of any such API may be subject to additional Terms of Service. You may not modify, rent, lease, loan, sell, distribute or create derivative works based on this data (either in whole or in part) unless allowed by the relevant Terms of Service.
2026-09-26T19:28:52.2937997Z 
2026-09-26T19:28:52.2941666Z 8.1.2 If you use any API to retrieve a user's data from Google, you acknowledge and agree that you shall retrieve data only with the user's explicit consent and only when, and for the limited purposes for which, the user has given you permission to do so. If you use the Android Recognition Service API, documented at the following URL: https://developer.android.com/reference/android/speech/RecognitionService, as updated from time to time, you acknowledge that the use of the API is subject to the Data Processing Addendum for Products where Google is a Data Processor, which is located at the following URL: https://privacy.google.com/businesses/gdprprocessorterms/, as updated from time to time. By clicking to accept, you hereby agree to the terms of the Data Processing Addendum for Products where Google is a Data Processor.
2026-09-26T19:28:52.2944438Z 
2026-09-26T19:28:52.2944443Z 
2026-09-26T19:28:52.2944559Z 9. Terminating the License Agreement
2026-09-26T19:28:52.2944770Z 
2026-09-26T19:28:52.2945081Z 9.1 The License Agreement will continue to apply until terminated by either you or Google as set out below.
2026-09-26T19:28:52.2945691Z 
2026-09-26T19:28:52.2946231Z 9.2 If you want to terminate the License Agreement, you may do so by ceasing your use of the SDK and any relevant developer credentials.
2026-09-26T19:28:52.2946714Z 
2026-09-26T19:28:52.2948872Z 9.3 Google may at any time, terminate the License Agreement with you if: (A) you have breached any provision of the License Agreement; or (B) Google is required to do so by law; or (C) the partner with whom Google offered certain parts of SDK (such as APIs) to you has terminated its relationship with Google or ceased to offer certain parts of the SDK to you; or (D) Google decides to no longer provide the SDK or certain parts of the SDK to users in the country in which you are resident or from which you use the service, or the provision of the SDK or certain SDK services to you by Google is, in Google's sole discretion, no longer commercially viable.
2026-09-26T19:28:52.2951279Z 
2026-09-26T19:28:52.2952680Z 9.4 When the License Agreement comes to an end, all of the legal rights, obligations and liabilities that you and Google have benefited from, been subject to (or which have accrued over time whilst the License Agreement has been in force) or which are expressed to continue indefinitely, shall be unaffected by this cessation, and the provisions of paragraph 14.7 shall continue to apply to such rights, obligations and liabilities indefinitely.
2026-09-26T19:28:52.2954151Z 
2026-09-26T19:28:52.2954155Z 
2026-09-26T19:28:52.2954250Z 10. DISCLAIMER OF WARRANTIES
2026-09-26T19:28:52.2954409Z 
2026-09-26T19:28:52.2954931Z 10.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT YOUR USE OF THE SDK IS AT YOUR SOLE RISK AND THAT THE SDK IS PROVIDED "AS IS" AND "AS AVAILABLE" WITHOUT WARRANTY OF ANY KIND FROM GOOGLE.
2026-09-26T19:28:52.2955522Z 
2026-09-26T19:28:52.2956345Z 10.2 YOUR USE OF THE SDK AND ANY MATERIAL DOWNLOADED OR OTHERWISE OBTAINED THROUGH THE USE OF THE SDK IS AT YOUR OWN DISCRETION AND RISK AND YOU ARE SOLELY RESPONSIBLE FOR ANY DAMAGE TO YOUR COMPUTER SYSTEM OR OTHER DEVICE OR LOSS OF DATA THAT RESULTS FROM SUCH USE.
2026-09-26T19:28:52.2957254Z 
2026-09-26T19:28:52.2958039Z 10.3 GOOGLE FURTHER EXPRESSLY DISCLAIMS ALL WARRANTIES AND CONDITIONS OF ANY KIND, WHETHER EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE IMPLIED WARRANTIES AND CONDITIONS OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT.
2026-09-26T19:28:52.2958952Z 
2026-09-26T19:28:52.2958956Z 
2026-09-26T19:28:52.2959049Z 11. LIMITATION OF LIABILITY
2026-09-26T19:28:52.2959217Z 
2026-09-26T19:28:52.2960907Z 11.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT GOOGLE, ITS SUBSIDIARIES AND AFFILIATES, AND ITS LICENSORS SHALL NOT BE LIABLE TO YOU UNDER ANY THEORY OF LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL OR EXEMPLARY DAMAGES THAT MAY BE INCURRED BY YOU, INCLUDING ANY LOSS OF DATA, WHETHER OR NOT GOOGLE OR ITS REPRESENTATIVES HAVE BEEN ADVISED OF OR SHOULD HAVE BEEN AWARE OF THE POSSIBILITY OF ANY SUCH LOSSES ARISING.
2026-09-26T19:28:52.2962365Z 
2026-09-26T19:28:52.2962370Z 
2026-09-26T19:28:52.2962452Z 12. Indemnification
2026-09-26T19:28:52.2962579Z 
2026-09-26T19:28:52.2965069Z 12.1 To the maximum extent permitted by law, you agree to defend, indemnify and hold harmless Google, its affiliates and their respective directors, officers, employees and agents from and against any and all claims, actions, suits or proceedings, as well as any and all losses, liabilities, damages, costs and expenses (including reasonable attorneys fees) arising out of or accruing from (a) your use of the SDK, (b) any application you develop on the SDK that infringes any copyright, trademark, trade secret, trade dress, patent or other intellectual property right of any person or defames any person or violates their rights of publicity or privacy, and (c) any non-compliance by you with the License Agreement.
2026-09-26T19:28:52.2967403Z 
2026-09-26T19:28:52.2967407Z 
2026-09-26T19:28:52.2967506Z 13. Changes to the License Agreement
2026-09-26T19:28:52.2967684Z 
2026-09-26T19:28:52.2968393Z 13.1 Google may make changes to the License Agreement as it distributes new versions of the SDK. When these changes are made, Google will make a new version of the License Agreement available on the website where the SDK is made available.
2026-09-26T19:28:52.2969191Z 
2026-09-26T19:28:52.2969195Z 
2026-09-26T19:28:52.2969277Z 14. General Legal Terms
2026-09-26T19:28:52.2969856Z 
2026-09-26T19:28:52.2970971Z 14.1 The License Agreement constitutes the whole legal agreement between you and Google and governs your use of the SDK (excluding any services which Google may provide to you under a separate written agreement), and completely replaces any prior agreements between you and Google in relation to the SDK.
2026-09-26T19:28:52.2972029Z 
2026-09-26T19:28:52.2973006Z 14.2 You agree that if Google does not exercise or enforce any legal right or remedy which is contained in the License Agreement (or which Google has the benefit of under any applicable law), this will not be taken to be a formal waiver of Google's rights and that those rights or remedies will still be available to Google.
2026-09-26T19:28:52.2974175Z 
2026-09-26T19:28:52.2975241Z 14.3 If any court of law, having the jurisdiction to decide on this matter, rules that any provision of the License Agreement is invalid, then that provision will be removed from the License Agreement without affecting the rest of the License Agreement. The remaining provisions of the License Agreement will continue to be valid and enforceable.
2026-09-26T19:28:52.2976397Z 
2026-09-26T19:28:52.2977794Z 14.4 You acknowledge and agree that each member of the group of companies of which Google is the parent shall be third party beneficiaries to the License Agreement and that such other companies shall be entitled to directly enforce, and rely upon, any provision of the License Agreement that confers a benefit on (or rights in favor of) them. Other than this, no other person or company shall be third party beneficiaries to the License Agreement.
2026-09-26T19:28:52.2979275Z 
2026-09-26T19:28:52.2980672Z 14.5 EXPORT RESTRICTIONS. THE SDK IS SUBJECT TO UNITED STATES EXPORT LAWS AND REGULATIONS. YOU MUST COMPLY WITH ALL DOMESTIC AND INTERNATIONAL EXPORT LAWS AND REGULATIONS THAT APPLY TO THE SDK. THESE LAWS INCLUDE RESTRICTIONS ON DESTINATIONS, END USERS AND END USE.
2026-09-26T19:28:52.2981590Z 
2026-09-26T19:28:52.2982653Z 14.6 The rights granted in the License Agreement may not be assigned or transferred by either you or Google without the prior written approval of the other party. Neither you nor Google shall be permitted to delegate their responsibilities or obligations under the License Agreement without the prior written approval of the other party.
2026-09-26T19:28:52.2983802Z 
2026-09-26T19:28:52.2985573Z 14.7 The License Agreement, and your relationship with Google under the License Agreement, shall be governed by the laws of the State of California without regard to its conflict of laws provisions. You and Google agree to submit to the exclusive jurisdiction of the courts located within the county of Santa Clara, California to resolve any legal matter arising from the License Agreement. Notwithstanding this, you agree that Google shall still be allowed to apply for injunctive remedies (or an equivalent type of urgent legal relief) in any jurisdiction.
2026-09-26T19:28:52.2987437Z 
2026-09-26T19:28:52.2987441Z 
2026-09-26T19:28:52.2987585Z January 16, 2019
2026-09-26T19:28:52.2987936Z ---------------------------------------
2026-09-26T19:28:52.2988382Z Accept? (y/N): 
2026-09-26T19:28:52.2988775Z 4/6: License android-sdk-preview-license:
2026-09-26T19:28:52.2989270Z ---------------------------------------
2026-09-26T19:28:52.2991129Z To get started with the Android SDK Preview, you must agree to the following terms and conditions. As described below, please note that this is a preview version of the Android SDK, subject to change, that you use at your own risk. The Android SDK Preview is not a stable release, and may contain errors and defects that can result in serious damage to your computer systems, devices and data.
2026-09-26T19:28:52.2992978Z 
2026-09-26T19:28:52.2993190Z This is the Android SDK Preview License Agreement (the "License Agreement").
2026-09-26T19:28:52.2993494Z 
2026-09-26T19:28:52.2993571Z 1. Introduction
2026-09-26T19:28:52.2993690Z 
2026-09-26T19:28:52.2995271Z 1.1 The Android SDK Preview (referred to in the License Agreement as the “Preview” and specifically including the Android system files, packaged APIs, and Preview library files, if and when they are made available) is licensed to you subject to the terms of the License Agreement. The License Agreement forms a legally binding contract between you and Google in relation to your use of the Preview.
2026-09-26T19:28:52.2996804Z 
2026-09-26T19:28:52.2997480Z 1.2 "Android" means the Android software stack for devices, as made available under the Android Open Source Project, which is located at the following URL: http://source.android.com/, as updated from time to time.
2026-09-26T19:28:52.2998361Z 
2026-09-26T19:28:52.2998825Z 1.3 "Google" means Google Inc., a Delaware corporation with principal place of business at 1600 Amphitheatre Parkway, Mountain View, CA 94043, United States.
2026-09-26T19:28:52.2999602Z 
2026-09-26T19:28:52.2999709Z 2. Accepting the License Agreement
2026-09-26T19:28:52.2999886Z 
2026-09-26T19:28:52.3000312Z 2.1 In order to use the Preview, you must first agree to the License Agreement. You may not use the Preview if you do not accept the License Agreement.
2026-09-26T19:28:52.3000822Z 
2026-09-26T19:28:52.3001113Z 2.2 By clicking to accept and/or using the Preview, you hereby agree to the terms of the License Agreement.
2026-09-26T19:28:52.3001486Z 
2026-09-26T19:28:52.3002284Z 2.3 You may not use the Preview and may not accept the License Agreement if you are a person barred from receiving the Preview under the laws of the United States or other countries including the country in which you are resident or from which you use the Preview.
2026-09-26T19:28:52.3003168Z 
2026-09-26T19:28:52.3004530Z 2.4 If you will use the Preview internally within your company or organization you agree to be bound by the License Agreement on behalf of your employer or other entity, and you represent and warrant that you have full legal authority to bind your employer or such entity to the License Agreement. If you do not have the requisite authority, you may not accept the License Agreement or use the Preview on behalf of your employer or other entity.
2026-09-26T19:28:52.3005974Z 
2026-09-26T19:28:52.3006071Z 3. Preview License from Google
2026-09-26T19:28:52.3006229Z 
2026-09-26T19:28:52.3007212Z 3.1 Subject to the terms of the License Agreement, Google grants you a royalty-free, non-assignable, non-exclusive, non-sublicensable, limited, revocable license to use the Preview, personally or internally within your company or organization, solely to develop applications to run on the Android platform.
2026-09-26T19:28:52.3008276Z 
2026-09-26T19:28:52.3009810Z 3.2 You agree that Google or third parties owns all legal right, title and interest in and to the Preview, including any Intellectual Property Rights that subsist in the Preview. "Intellectual Property Rights" means any and all rights under patent law, copyright law, trade secret law, trademark law, and any and all other proprietary rights. Google reserves all rights not expressly granted to you.
2026-09-26T19:28:52.3011175Z 
2026-09-26T19:28:52.3013069Z 3.3 You may not use the Preview for any purpose not expressly permitted by the License Agreement. Except to the extent required by applicable third party licenses, you may not: (a) copy (except for backup purposes), modify, adapt, redistribute, decompile, reverse engineer, disassemble, or create derivative works of the Preview or any part of the Preview; or (b) load any part of the Preview onto a mobile handset or any other hardware device except a personal computer, combine any part of the Preview with other software, or distribute any software or device incorporating a part of the Preview.
2026-09-26T19:28:52.3015029Z 
2026-09-26T19:28:52.3015806Z 3.4 You agree that you will not take any actions that may cause or result in the fragmentation of Android, including but not limited to distributing, participating in the creation of, or promoting in any way a software development kit derived from the Preview.
2026-09-26T19:28:52.3016673Z 
2026-09-26T19:28:52.3018155Z 3.5 Use, reproduction and distribution of components of the Preview licensed under an open source software license are governed solely by the terms of that open source software license and not the License Agreement. You agree to remain a licensee in good standing in regard to such open source software licenses under all the rights granted and to refrain from any actions that may terminate, suspend, or breach such rights.
2026-09-26T19:28:52.3019987Z 
2026-09-26T19:28:52.3021392Z 3.6 You agree that the form and nature of the Preview that Google provides may change without prior notice to you and that future versions of the Preview may be incompatible with applications developed on previous versions of the Preview. You agree that Google may stop (permanently or temporarily) providing the Preview (or any features within the Preview) to you or to users generally at Google's sole discretion, without prior notice to you.
2026-09-26T19:28:52.3022868Z 
2026-09-26T19:28:52.3023371Z 3.7 Nothing in the License Agreement gives you a right to use any of Google's trade names, trademarks, service marks, logos, domain names, or other distinctive brand features.
2026-09-26T19:28:52.3023974Z 
2026-09-26T19:28:52.3024540Z 3.8 You agree that you will not remove, obscure, or alter any proprietary rights notices (including copyright and trademark notices) that may be affixed to or contained within the Preview.
2026-09-26T19:28:52.3025189Z 
2026-09-26T19:28:52.3025298Z 4. Use of the Preview by You
2026-09-26T19:28:52.3025450Z 
2026-09-26T19:28:52.3026392Z 4.1 Google agrees that nothing in the License Agreement gives Google any right, title or interest from you (or your licensors) under the License Agreement in or to any software applications that you develop using the Preview, including any intellectual property rights that subsist in those applications.
2026-09-26T19:28:52.3027419Z 
2026-09-26T19:28:52.3028525Z 4.2 You agree to use the Preview and write applications only for purposes that are permitted by (a) the License Agreement, and (b) any applicable law, regulation or generally accepted practices or guidelines in the relevant jurisdictions (including any laws regarding the export of data or software to and from the United States or other relevant countries).
2026-09-26T19:28:52.3030107Z 
2026-09-26T19:28:52.3032454Z 4.3 You agree that if you use the Preview to develop applications, you will protect the privacy and legal rights of users. If users provide you with user names, passwords, or other login information or personal information, you must make the users aware that the information will be available to your application, and you must provide legally adequate privacy notice and protection for those users. If your application stores personal or sensitive information provided by users, it must do so securely. If users provide you with Google Account information, your application may only use that information to access the user's Google Account when, and for the limited purposes for which, each user has given you permission to do so.
2026-09-26T19:28:52.3034855Z 
2026-09-26T19:28:52.3035765Z 4.4 You agree that you will not engage in any activity with the Preview, including the development or distribution of an application, that interferes with, disrupts, damages, or accesses in an unauthorized manner the servers, networks, or other properties or services of Google or any third party.
2026-09-26T19:28:52.3036766Z 
2026-09-26T19:28:52.3037834Z 4.5 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any data, content, or resources that you create, transmit or display through Android and/or applications for Android, and for the consequences of your actions (including any loss or damage which Google may suffer) by doing so.
2026-09-26T19:28:52.3038991Z 
2026-09-26T19:28:52.3040416Z 4.6 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any breach of your obligations under the License Agreement, any applicable third party contract or Terms of Service, or any applicable law or regulation, and for the consequences (including any loss or damage which Google or any third party may suffer) of any such breach.
2026-09-26T19:28:52.3041728Z 
2026-09-26T19:28:52.3044186Z 4.7 The Preview is in development, and your testing and feedback are an important part of the development process. By using the Preview, you acknowledge that implementation of some features are still under development and that you should not rely on the Preview having the full functionality of a stable release. You agree not to publicly distribute or ship any application using this Preview as this Preview will no longer be supported after the official Android SDK is released.
2026-09-26T19:28:52.3045922Z 
2026-09-26T19:28:52.3046027Z 5. Your Developer Credentials
2026-09-26T19:28:52.3046192Z 
2026-09-26T19:28:52.3047070Z 5.1 You agree that you are responsible for maintaining the confidentiality of any developer credentials that may be issued to you by Google or which you may choose yourself and that you will be solely responsible for all applications that are developed under your developer credentials.
2026-09-26T19:28:52.3048041Z 
2026-09-26T19:28:52.3048131Z 6. Privacy and Information
2026-09-26T19:28:52.3048283Z 
2026-09-26T19:28:52.3050150Z 6.1 In order to continually innovate and improve the Preview, Google may collect certain usage statistics from the software including but not limited to a unique identifier, associated IP address, version number of the software, and information on which tools and/or services in the Preview are being used and how they are being used. Before any of this information is collected, the Preview will notify you and seek your consent. If you withhold consent, the information will not be collected.
2026-09-26T19:28:52.3051806Z 
2026-09-26T19:28:52.3052390Z 6.2 The data collected is examined in the aggregate to improve the Preview and is maintained in accordance with Google's Privacy Policy located at http://www.google.com/policies/privacy/.
2026-09-26T19:28:52.3053057Z 
2026-09-26T19:28:52.3053148Z 7. Third Party Applications
2026-09-26T19:28:52.3053302Z 
2026-09-26T19:28:52.3055174Z 7.1 If you use the Preview to run applications developed by a third party or that access data, content or resources provided by a third party, you agree that Google is not responsible for those applications, data, content, or resources. You understand that all data, content or resources which you may access through such third party applications are the sole responsibility of the person from which they originated and that Google is not liable for any loss or damage that you may experience as a result of the use or access of any of those third party applications, data, content, or resources.
2026-09-26T19:28:52.3057116Z 
2026-09-26T19:28:52.3058664Z 7.2 You should be aware the data, content, and resources presented to you through such a third party application may be protected by intellectual property rights which are owned by the providers (or by other persons or companies on their behalf). You may not modify, rent, lease, loan, sell, distribute or create derivative works based on these data, content, or resources (either in whole or in part) unless you have been specifically given permission to do so by the relevant owners.
2026-09-26T19:28:52.3060462Z 
2026-09-26T19:28:52.3060980Z 7.3 You acknowledge that your use of such third party applications, data, content, or resources may be subject to separate terms between you and the relevant third party.
2026-09-26T19:28:52.3061570Z 
2026-09-26T19:28:52.3061657Z 8. Using Google APIs
2026-09-26T19:28:52.3061786Z 
2026-09-26T19:28:52.3061869Z 8.1 Google APIs
2026-09-26T19:28:52.3061981Z 
2026-09-26T19:28:52.3063524Z 8.1.1 If you use any API to retrieve data from Google, you acknowledge that the data may be protected by intellectual property rights which are owned by Google or those parties that provide the data (or by other persons or companies on their behalf). Your use of any such API may be subject to additional Terms of Service. You may not modify, rent, lease, loan, sell, distribute or create derivative works based on this data (either in whole or in part) unless allowed by the relevant Terms of Service.
2026-09-26T19:28:52.3065140Z 
2026-09-26T19:28:52.3066029Z 8.1.2 If you use any API to retrieve a user's data from Google, you acknowledge and agree that you shall retrieve data only with the user's explicit consent and only when, and for the limited purposes for which, the user has given you permission to do so.
2026-09-26T19:28:52.3066976Z 
2026-09-26T19:28:52.3067077Z 9. Terminating the License Agreement
2026-09-26T19:28:52.3067267Z 
2026-09-26T19:28:52.3067566Z 9.1 the License Agreement will continue to apply until terminated by either you or Google as set out below.
2026-09-26T19:28:52.3067962Z 
2026-09-26T19:28:52.3068641Z 9.2 If you want to terminate the License Agreement, you may do so by ceasing your use of the Preview and any relevant developer credentials.
2026-09-26T19:28:52.3069721Z 
2026-09-26T19:28:52.3070218Z 9.3 Google may at any time, terminate the License Agreement, with or without cause, upon notice to you.
2026-09-26T19:28:52.3070907Z 
2026-09-26T19:28:52.3072902Z 9.4 The License Agreement will automatically terminate without notice or other action upon the earlier of: (A) when Google ceases to provide the Preview or certain parts of the Preview to users in the country in which you are resident or from which you use the service; and (B) Google issues a final release version of the Android SDK.
2026-09-26T19:28:52.3074945Z 
2026-09-26T19:28:52.3076291Z 9.5 When the License Agreement is terminated, the license granted to you in the License Agreement will terminate, you will immediately cease all use of the Preview, and the provisions of paragraphs 10, 11, 12 and 14 shall survive indefinitely.
2026-09-26T19:28:52.3077877Z 
2026-09-26T19:28:52.3078013Z 10. DISCLAIMERS
2026-09-26T19:28:52.3078221Z 
2026-09-26T19:28:52.3079212Z 10.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT YOUR USE OF THE PREVIEW IS AT YOUR SOLE RISK AND THAT THE PREVIEW IS PROVIDED "AS IS" AND "AS AVAILABLE" WITHOUT WARRANTY OF ANY KIND FROM GOOGLE.
2026-09-26T19:28:52.3080594Z 
2026-09-26T19:28:52.3083817Z 10.2 YOUR USE OF THE PREVIEW AND ANY MATERIAL DOWNLOADED OR OTHERWISE OBTAINED THROUGH THE USE OF THE PREVIEW IS AT YOUR OWN DISCRETION AND RISK AND YOU ARE SOLELY RESPONSIBLE FOR ANY DAMAGE TO YOUR COMPUTER SYSTEM OR OTHER DEVICE OR LOSS OF DATA THAT RESULTS FROM SUCH USE. WITHOUT LIMITING THE FOREGOING, YOU UNDERSTAND THAT THE PREVIEW IS NOT A STABLE RELEASE AND MAY CONTAIN ERRORS, DEFECTS AND SECURITY VULNERABILITIES THAT CAN RESULT IN SIGNIFICANT DAMAGE, INCLUDING THE COMPLETE, IRRECOVERABLE LOSS OF USE OF YOUR COMPUTER SYSTEM OR OTHER DEVICE.
2026-09-26T19:28:52.3087201Z 
2026-09-26T19:28:52.3088472Z 10.3 GOOGLE FURTHER EXPRESSLY DISCLAIMS ALL WARRANTIES AND CONDITIONS OF ANY KIND, WHETHER EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE IMPLIED WARRANTIES AND CONDITIONS OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT.
2026-09-26T19:28:52.3089632Z 
2026-09-26T19:28:52.3089746Z 11. LIMITATION OF LIABILITY
2026-09-26T19:28:52.3089903Z 
2026-09-26T19:28:52.3091295Z 11.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT GOOGLE, ITS SUBSIDIARIES AND AFFILIATES, AND ITS LICENSORS SHALL NOT BE LIABLE TO YOU UNDER ANY THEORY OF LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL OR EXEMPLARY DAMAGES THAT MAY BE INCURRED BY YOU, INCLUDING ANY LOSS OF DATA, WHETHER OR NOT GOOGLE OR ITS REPRESENTATIVES HAVE BEEN ADVISED OF OR SHOULD HAVE BEEN AWARE OF THE POSSIBILITY OF ANY SUCH LOSSES ARISING.
2026-09-26T19:28:52.3092762Z 
2026-09-26T19:28:52.3092847Z 12. Indemnification
2026-09-26T19:28:52.3092974Z 
2026-09-26T19:28:52.3095766Z 12.1 To the maximum extent permitted by law, you agree to defend, indemnify and hold harmless Google, its affiliates and their respective directors, officers, employees and agents from and against any and all claims, actions, suits or proceedings, as well as any and all losses, liabilities, damages, costs and expenses (including reasonable attorneys’ fees) arising out of or accruing from (a) your use of the Preview, (b) any application you develop on the Preview that infringes any Intellectual Property Rights of any person or defames any person or violates their rights of publicity or privacy, and (c) any non-compliance by you of the License Agreement.
2026-09-26T19:28:52.3097978Z 
2026-09-26T19:28:52.3098191Z 13. Changes to the License Agreement
2026-09-26T19:28:52.3098369Z 
2026-09-26T19:28:52.3099122Z 13.1 Google may make changes to the License Agreement as it distributes new versions of the Preview. When these changes are made, Google will make a new version of the License Agreement available on the website where the Preview is made available.
2026-09-26T19:28:52.3100278Z 
2026-09-26T19:28:52.3100373Z 14. General Legal Terms
2026-09-26T19:28:52.3100517Z 
2026-09-26T19:28:52.3101513Z 14.1 the License Agreement constitutes the whole legal agreement between you and Google and governs your use of the Preview (excluding any services which Google may provide to you under a separate written agreement), and completely replaces any prior agreements between you and Google in relation to the Preview.
2026-09-26T19:28:52.3102594Z 
2026-09-26T19:28:52.3103577Z 14.2 You agree that if Google does not exercise or enforce any legal right or remedy which is contained in the License Agreement (or which Google has the benefit of under any applicable law), this will not be taken to be a formal waiver of Google's rights and that those rights or remedies will still be available to Google.
2026-09-26T19:28:52.3104646Z 
2026-09-26T19:28:52.3105704Z 14.3 If any court of law, having the jurisdiction to decide on this matter, rules that any provision of the License Agreement is invalid, then that provision will be removed from the License Agreement without affecting the rest of the License Agreement. The remaining provisions of the License Agreement will continue to be valid and enforceable.
2026-09-26T19:28:52.3106854Z 
2026-09-26T19:28:52.3108243Z 14.4 You acknowledge and agree that each member of the group of companies of which Google is the parent shall be third party beneficiaries to the License Agreement and that such other companies shall be entitled to directly enforce, and rely upon, any provision of the License Agreement that confers a benefit on (or rights in favor of) them. Other than this, no other person or company shall be third party beneficiaries to the License Agreement.
2026-09-26T19:28:52.3109900Z 
2026-09-26T19:28:52.3110757Z 14.5 EXPORT RESTRICTIONS. THE PREVIEW IS SUBJECT TO UNITED STATES EXPORT LAWS AND REGULATIONS. YOU MUST COMPLY WITH ALL DOMESTIC AND INTERNATIONAL EXPORT LAWS AND REGULATIONS THAT APPLY TO THE PREVIEW. THESE LAWS INCLUDE RESTRICTIONS ON DESTINATIONS, END USERS AND END USE.
2026-09-26T19:28:52.3111697Z 
2026-09-26T19:28:52.3112671Z 14.6 The License Agreement may not be assigned or transferred by you without the prior written approval of Google, and any attempted assignment without such approval will be void. You shall not delegate your responsibilities or obligations under the License Agreement without the prior written approval of Google.
2026-09-26T19:28:52.3113872Z 
2026-09-26T19:28:52.3116068Z 14.7 The License Agreement, and your relationship with Google under the License Agreement, shall be governed by the laws of the State of California without regard to its conflict of laws provisions. You and Google agree to submit to the exclusive jurisdiction of the courts located within the county of Santa Clara, California to resolve any legal matter arising from the License Agreement. Notwithstanding this, you agree that Google shall still be allowed to apply for injunctive remedies (or an equivalent type of urgent legal relief) in any jurisdiction.
2026-09-26T19:28:52.3118178Z 
2026-09-26T19:28:52.3118261Z June 2014.
2026-09-26T19:28:52.3118466Z ---------------------------------------
2026-09-26T19:28:52.3118724Z Accept? (y/N): 
2026-09-26T19:28:52.3119028Z 5/6: License google-gdk-license:
2026-09-26T19:28:52.3119292Z ---------------------------------------
2026-09-26T19:28:52.3119771Z This is a Developer Preview of the GDK that is subject to change.
2026-09-26T19:28:52.3120143Z 
2026-09-26T19:28:52.3120257Z Terms and Conditions
2026-09-26T19:28:52.3120388Z 
2026-09-26T19:28:52.3120678Z This is the Glass Development Kit License Agreement.
2026-09-26T19:28:52.3120913Z 
2026-09-26T19:28:52.3120995Z 1. Introduction
2026-09-26T19:28:52.3121301Z 
2026-09-26T19:28:52.3122664Z 1.1 The Glass Development Kit (referred to in this License Agreement as the "GDK" and specifically including the Android system files, packaged APIs, and GDK library files, if and when they are made available) is licensed to you subject to the terms of this License Agreement. This License Agreement forms a legally binding contract between you and Google in relation to your use of the GDK.
2026-09-26T19:28:52.3124085Z 
2026-09-26T19:28:52.3124316Z 1.2 "Glass" means Glass devices and the Glass software stack for use on Glass devices.
2026-09-26T19:28:52.3124674Z 
2026-09-26T19:28:52.3124681Z 
2026-09-26T19:28:52.3125409Z 1.3 "Android" means the Android software stack for devices, as made available under the Android Open Source Project, which is located at the following URL: http://source.android.com/, as updated from time to time.
2026-09-26T19:28:52.3126242Z 
2026-09-26T19:28:52.3126727Z 1.4 "Google" means Google Inc., a Delaware corporation with principal place of business at 1600 Amphitheatre Parkway, Mountain View, CA 94043, United States.
2026-09-26T19:28:52.3127446Z 
2026-09-26T19:28:52.3127549Z 2. Accepting this License Agreement
2026-09-26T19:28:52.3127731Z 
2026-09-26T19:28:52.3128184Z 2.1 In order to use the GDK, you must first agree to this License Agreement. You may not use the GDK if you do not accept this License Agreement.
2026-09-26T19:28:52.3128754Z 
2026-09-26T19:28:52.3128976Z 2.2 By clicking to accept, you hereby agree to the terms of this License Agreement.
2026-09-26T19:28:52.3129465Z 
2026-09-26T19:28:52.3130279Z 2.3 You may not use the GDK and may not accept the License Agreement if you are a person barred from receiving the GDK under the laws of the United States or other countries including the country in which you are resident or from which you use the GDK.
2026-09-26T19:28:52.3131258Z 
2026-09-26T19:28:52.3133268Z 2.4 If you are agreeing to be bound by this License Agreement on behalf of your employer or other entity, you represent and warrant that you have full legal authority to bind your employer or such entity to this License Agreement. If you do not have the requisite authority, you may not accept the License Agreement or use the GDK on behalf of your employer or other entity.
2026-09-26T19:28:52.3135495Z 
2026-09-26T19:28:52.3135668Z 3. GDK License from Google
2026-09-26T19:28:52.3135927Z 
2026-09-26T19:28:52.3137255Z 3.1 Subject to the terms of this License Agreement, Google grants you a limited, worldwide, royalty-free, non-assignable and non-exclusive license to use the GDK solely to develop applications to run on the Glass platform for Glass devices.
2026-09-26T19:28:52.3138765Z 
2026-09-26T19:28:52.3141233Z 3.2 You agree that Google or third parties own all legal right, title and interest in and to the GDK, including any Intellectual Property Rights that subsist in the GDK. "Intellectual Property Rights" means any and all rights under patent law, copyright law, trade secret law, trademark law, and any and all other proprietary rights. Google reserves all rights not expressly granted to you.
2026-09-26T19:28:52.3143615Z 
2026-09-26T19:28:52.3147122Z 3.3 You may not use the GDK for any purpose not expressly permitted by this License Agreement. Except to the extent required by applicable third party licenses, you may not: (a) copy (except for backup purposes), modify, adapt, redistribute, decompile, reverse engineer, disassemble, or create derivative works of the GDK or any part of the GDK; or (b) load any part of the GDK onto a mobile handset or wearable computing device or any other hardware device except a Glass device personal computer, combine any part of the GDK with other software, or distribute any software or device incorporating a part of the GDK.
2026-09-26T19:28:52.3150868Z 
2026-09-26T19:28:52.3151905Z 3.4 You agree that you will not take any actions that may cause or result in the fragmentation of Glass, including but not limited to distributing, participating in the creation of, or promoting in any way a software development kit derived from the GDK.
2026-09-26T19:28:52.3153054Z 
2026-09-26T19:28:52.3153860Z 3.5 Use, reproduction and distribution of components of the GDK licensed under an open source software license are governed solely by the terms of that open source software license and not this License Agreement.
2026-09-26T19:28:52.3154746Z 
2026-09-26T19:28:52.3156172Z 3.6 You agree that the form and nature of the GDK that Google provides may change without prior notice to you and that future versions of the GDK may be incompatible with applications developed on previous versions of the GDK. You agree that Google may stop (permanently or temporarily) providing the GDK (or any features within the GDK) to you or to users generally at Google's sole discretion, without prior notice to you.
2026-09-26T19:28:52.3157687Z 
2026-09-26T19:28:52.3158314Z 3.7 Nothing in this License Agreement gives you a right to use any of Google's trade names, trademarks, service marks, logos, domain names, or other distinctive brand features.
2026-09-26T19:28:52.3158966Z 
2026-09-26T19:28:52.3159849Z 3.8 You agree that you will not remove, obscure, or alter any proprietary rights notices (including copyright and trademark notices) that may be affixed to or contained within the GDK.
2026-09-26T19:28:52.3160658Z 
2026-09-26T19:28:52.3160662Z 
2026-09-26T19:28:52.3161913Z 3.9 Your use of any Android system files, packaged APIs, or other components of the GDK which are part of the Android Software Development Kit is subject to the terms of the Android Software Development Kit License Agreement located at http://developer.android.com/sdk/terms.html. These terms are hereby incorporated by reference into this License Agreement.
2026-09-26T19:28:52.3163246Z 
2026-09-26T19:28:52.3163343Z 4. Use of the GDK by You
2026-09-26T19:28:52.3163490Z 
2026-09-26T19:28:52.3164424Z 4.1 Google agrees that it obtains no right, title or interest from you (or your licensors) under this License Agreement in or to any software applications that you develop using the GDK, including any intellectual property rights that subsist in those applications.
2026-09-26T19:28:52.3165443Z 
2026-09-26T19:28:52.3167341Z 4.2 You agree to use the GDK and write applications only for purposes that are permitted by (a) this License Agreement, (b) the Glass Platform Developer Policies (located at https://developers.google.com/glass/policies, and hereby incorporated into this License Agreement by reference), and (c) any applicable law, regulation or generally accepted practices or guidelines in the relevant jurisdictions (including any laws regarding the export of data or software to and from the United States or other relevant countries).
2026-09-26T19:28:52.3169206Z 
2026-09-26T19:28:52.3172169Z 4.3 You agree that if you use the GDK to develop applications for general public users, you will protect the privacy and legal rights of those users. If the users provide you with user names, passwords, or other login information or personal information, you must make the users aware that the information will be available to your application, and you must provide legally adequate privacy notice and protection for those users. If your application stores personal or sensitive information provided by users, it must do so securely. If the user provides your application with Google Account information, your application may only use that information to access the user's Google Account when, and for the limited purposes for which, the user has given you permission to do so.
2026-09-26T19:28:52.3174948Z 
2026-09-26T19:28:52.3175948Z 4.4 You agree that you will not engage in any activity with the GDK, including the development or distribution of an application, that interferes with, disrupts, damages, or accesses in an unauthorized manner the servers, networks, or other properties or services of any third party including, but not limited to, Google.
2026-09-26T19:28:52.3177112Z 
2026-09-26T19:28:52.3178313Z 4.5 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any data, content, or resources that you create, transmit or display through Glass and/or applications for Glass, and for the consequences of your actions (including any loss or damage which Google may suffer) by doing so.
2026-09-26T19:28:52.3179778Z 
2026-09-26T19:28:52.3181114Z 4.6 You agree that you are solely responsible for (and that Google has no responsibility to you or to any third party for) any breach of your obligations under this License Agreement, any applicable third party contract or Terms of Service, or any applicable law or regulation, and for the consequences (including any loss or damage which Google or any third party may suffer) of any such breach.
2026-09-26T19:28:52.3182423Z 
2026-09-26T19:28:52.3182427Z 
2026-09-26T19:28:52.3183592Z 4.7 The GDK is in development, and your testing and feedback are an important part of the development process. By using the GDK, you acknowledge that implementation of some features are still under development and that you should not rely on the GDK, Glass devices, Glass system software, Google Mirror API, or Glass services having the full functionality of a stable release.
2026-09-26T19:28:52.3184839Z 
2026-09-26T19:28:52.3184936Z 5. Your Developer Credentials
2026-09-26T19:28:52.3185096Z 
2026-09-26T19:28:52.3185963Z 5.1 You agree that you are responsible for maintaining the confidentiality of any developer credentials that may be issued to you by Google or which you may choose yourself and that you will be solely responsible for all applications that are developed under your developer credentials.
2026-09-26T19:28:52.3186927Z 
2026-09-26T19:28:52.3187024Z 6. Privacy and Information
2026-09-26T19:28:52.3187167Z 
2026-09-26T19:28:52.3187172Z 
2026-09-26T19:28:52.3188794Z 6.1 In order to continually innovate and improve the GDK, Google may collect certain usage statistics from the software including but not limited to a unique identifier, associated IP address, version number of the software, and information on which tools and/or services in the GDK are being used and how they are being used. Before any of this information is collected, the GDK will notify you and seek your consent. If you withhold consent, the information will not be collected.
2026-09-26T19:28:52.3190629Z 
2026-09-26T19:28:52.3191012Z 6.2 The data collected is examined in the aggregate to improve the GDK and is maintained in accordance with Google's Privacy Policy.
2026-09-26T19:28:52.3191490Z 
2026-09-26T19:28:52.3191589Z 7. Third Party Applications
2026-09-26T19:28:52.3191739Z 
2026-09-26T19:28:52.3193576Z 7.1 If you use the GDK to run applications developed by a third party or that access data, content or resources provided by a third party, you agree that Google is not responsible for those applications, data, content, or resources. You understand that all data, content or resources which you may access through such third party applications are the sole responsibility of the person from which they originated and that Google is not liable for any loss or damage that you may experience as a result of the use or access of any of those third party applications, data, content, or resources.
2026-09-26T19:28:52.3195490Z 
2026-09-26T19:28:52.3196992Z 7.2 You should be aware the data, content, and resources presented to you through such a third party application may be protected by intellectual property rights which are owned by the providers (or by other persons or companies on their behalf). You may not modify, rent, lease, loan, sell, distribute or create derivative works based on these data, content, or resources (either in whole or in part) unless you have been specifically given permission to do so by the relevant owners.
2026-09-26T19:28:52.3198592Z 
2026-09-26T19:28:52.3199812Z 7.3 You acknowledge that your use of such third party applications, data, content, or resources may be subject to separate terms between you and the relevant third party. In that case, this License Agreement does not affect your legal relationship with these third parties.
2026-09-26T19:28:52.3200852Z 
2026-09-26T19:28:52.3200942Z 8. Using Google APIs
2026-09-26T19:28:52.3201169Z 
2026-09-26T19:28:52.3201266Z 8.1 Google APIs
2026-09-26T19:28:52.3201391Z 
2026-09-26T19:28:52.3202928Z 8.1.1 If you use any API to retrieve data from Google, you acknowledge that the data may be protected by intellectual property rights which are owned by Google or those parties that provide the data (or by other persons or companies on their behalf). Your use of any such API may be subject to additional Terms of Service. You may not modify, rent, lease, loan, sell, distribute or create derivative works based on this data (either in whole or in part) unless allowed by the relevant Terms of Service.
2026-09-26T19:28:52.3204544Z 
2026-09-26T19:28:52.3205300Z 8.1.2 If you use any API to retrieve a user's data from Google, you acknowledge and agree that you shall retrieve data only with the user's explicit consent and only when, and for the limited purposes for which, the user has given you permission to do so.
2026-09-26T19:28:52.3206142Z 
2026-09-26T19:28:52.3206242Z 9. Terminating this License Agreement
2026-09-26T19:28:52.3206426Z 
2026-09-26T19:28:52.3206727Z 9.1 This License Agreement will continue to apply until terminated by either you or Google as set out below.
2026-09-26T19:28:52.3207126Z 
2026-09-26T19:28:52.3207492Z 9.2 If you want to terminate this License Agreement, you may do so by ceasing your use of the GDK and any relevant developer credentials.
2026-09-26T19:28:52.3207958Z 
2026-09-26T19:28:52.3210133Z 9.3 Google may at any time, terminate this License Agreement with you if: (A) you have breached any provision of this License Agreement; or (B) Google is required to do so by law; or (C) the partner with whom Google offered certain parts of GDK (such as APIs) to you has terminated its relationship with Google or ceased to offer certain parts of the GDK to you; or (D) Google decides to no longer provide the GDK or certain parts of the GDK to users in the country in which you are resident or from which you use the service, or the provision of the GDK or certain GDK services to you by Google is, in Google's sole discretion, no longer commercially viable.
2026-09-26T19:28:52.3212196Z 
2026-09-26T19:28:52.3213576Z 9.4 When this License Agreement comes to an end, all of the legal rights, obligations and liabilities that you and Google have benefited from, been subject to (or which have accrued over time whilst this License Agreement has been in force) or which are expressed to continue indefinitely, shall be unaffected by this cessation, and the provisions of paragraph 14.7 shall continue to apply to such rights, obligations and liabilities indefinitely.
2026-09-26T19:28:52.3215046Z 
2026-09-26T19:28:52.3215144Z 10. DISCLAIMER OF WARRANTIES
2026-09-26T19:28:52.3215294Z 
2026-09-26T19:28:52.3215807Z 10.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT YOUR USE OF THE GDK IS AT YOUR SOLE RISK AND THAT THE GDK IS PROVIDED "AS IS" AND "AS AVAILABLE" WITHOUT WARRANTY OF ANY KIND FROM GOOGLE.
2026-09-26T19:28:52.3216413Z 
2026-09-26T19:28:52.3217199Z 10.2 YOUR USE OF THE GDK AND ANY MATERIAL DOWNLOADED OR OTHERWISE OBTAINED THROUGH THE USE OF THE GDK IS AT YOUR OWN DISCRETION AND RISK AND YOU ARE SOLELY RESPONSIBLE FOR ANY DAMAGE TO YOUR COMPUTER SYSTEM OR OTHER DEVICE OR LOSS OF DATA THAT RESULTS FROM SUCH USE.
2026-09-26T19:28:52.3218086Z 
2026-09-26T19:28:52.3218871Z 10.3 GOOGLE FURTHER EXPRESSLY DISCLAIMS ALL WARRANTIES AND CONDITIONS OF ANY KIND, WHETHER EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE IMPLIED WARRANTIES AND CONDITIONS OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT.
2026-09-26T19:28:52.3219948Z 
2026-09-26T19:28:52.3220052Z 11. LIMITATION OF LIABILITY
2026-09-26T19:28:52.3220206Z 
2026-09-26T19:28:52.3221719Z 11.1 YOU EXPRESSLY UNDERSTAND AND AGREE THAT GOOGLE, ITS SUBSIDIARIES AND AFFILIATES, AND ITS LICENSORS SHALL NOT BE LIABLE TO YOU UNDER ANY THEORY OF LIABILITY FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL OR EXEMPLARY DAMAGES THAT MAY BE INCURRED BY YOU, INCLUDING ANY LOSS OF DATA, WHETHER OR NOT GOOGLE OR ITS REPRESENTATIVES HAVE BEEN ADVISED OF OR SHOULD HAVE BEEN AWARE OF THE POSSIBILITY OF ANY SUCH LOSSES ARISING.
2026-09-26T19:28:52.3223272Z 
2026-09-26T19:28:52.3223355Z 12. Indemnification
2026-09-26T19:28:52.3223482Z 
2026-09-26T19:28:52.3225754Z 12.1 To the maximum extent permitted by law, you agree to defend, indemnify and hold harmless Google, its affiliates and their respective directors, officers, employees and agents from and against any and all claims, actions, suits or proceedings, as well as any and all losses, liabilities, damages, costs and expenses (including reasonable attorneys fees) arising out of or accruing from (a) your use of the GDK, (b) any application you develop on the GDK that infringes any copyright, trademark, trade secret, trade dress, patent or other intellectual property right of any person or defames any person or violates their rights of publicity or privacy, and (c) any non-compliance by you with this License Agreement.
2026-09-26T19:28:52.3228346Z 
2026-09-26T19:28:52.3228462Z 13. Changes to the License Agreement
2026-09-26T19:28:52.3228644Z 
2026-09-26T19:28:52.3229558Z 13.1 Google may make changes to the License Agreement as it distributes new versions of the GDK. When these changes are made, Google will make a new version of the License Agreement available on the website where the GDK is made available.
2026-09-26T19:28:52.3230390Z 
2026-09-26T19:28:52.3230476Z 14. General Legal Terms
2026-09-26T19:28:52.3230617Z 
2026-09-26T19:28:52.3231577Z 14.1 This License Agreement constitutes the whole legal agreement between you and Google and governs your use of the GDK (excluding any services which Google may provide to you under a separate written agreement), and completely replaces any prior agreements between you and Google in relation to the GDK.
2026-09-26T19:28:52.3232608Z 
2026-09-26T19:28:52.3233587Z 14.2 You agree that if Google does not exercise or enforce any legal right or remedy which is contained in this License Agreement (or which Google has the benefit of under any applicable law), this will not be taken to be a formal waiver of Google's rights and that those rights or remedies will still be available to Google.
2026-09-26T19:28:52.3234650Z 
2026-09-26T19:28:52.3235724Z 14.3 If any court of law, having the jurisdiction to decide on this matter, rules that any provision of this License Agreement is invalid, then that provision will be removed from this License Agreement without affecting the rest of this License Agreement. The remaining provisions of this License Agreement will continue to be valid and enforceable.
2026-09-26T19:28:52.3236892Z 
2026-09-26T19:28:52.3238291Z 14.4 You acknowledge and agree that each member of the group of companies of which Google is the parent shall be third party beneficiaries to this License Agreement and that such other companies shall be entitled to directly enforce, and rely upon, any provision of this License Agreement that confers a benefit on (or rights in favor of) them. Other than this, no other person or company shall be third party beneficiaries to this License Agreement.
2026-09-26T19:28:52.3239953Z 
2026-09-26T19:28:52.3240769Z 14.5 EXPORT RESTRICTIONS. THE GDK IS SUBJECT TO UNITED STATES EXPORT LAWS AND REGULATIONS. YOU MUST COMPLY WITH ALL DOMESTIC AND INTERNATIONAL EXPORT LAWS AND REGULATIONS THAT APPLY TO THE GDK. THESE LAWS INCLUDE RESTRICTIONS ON DESTINATIONS, END USERS AND END USE.
2026-09-26T19:28:52.3241675Z 
2026-09-26T19:28:52.3242749Z 14.6 The rights granted in this License Agreement may not be assigned or transferred by either you or Google without the prior written approval of the other party. Neither you nor Google shall be permitted to delegate their responsibilities or obligations under this License Agreement without the prior written approval of the other party.
2026-09-26T19:28:52.3243893Z 
2026-09-26T19:28:52.3245810Z 14.7 This License Agreement, and your relationship with Google under this License Agreement, shall be governed by the laws of the State of California without regard to its conflict of laws provisions. You and Google agree to submit to the exclusive jurisdiction of the courts located within the county of Santa Clara, California to resolve any legal matter arising from this License Agreement. Notwithstanding this, you agree that Google shall still be allowed to apply for injunctive remedies (or an equivalent type of urgent legal relief) in any jurisdiction.
2026-09-26T19:28:52.3247771Z 
2026-09-26T19:28:52.3247850Z November 19, 2013
2026-09-26T19:28:52.3248060Z ---------------------------------------
2026-09-26T19:28:52.3248319Z Accept? (y/N): 
2026-09-26T19:28:52.3248549Z 6/6: License mips-android-sysimage-license:
2026-09-26T19:28:52.3248832Z ---------------------------------------
2026-09-26T19:28:52.3251285Z MIPS Technologies, Inc. (“MIPS”) Internal Evaluation License Agreement for MIPS Android™ System Images for Android Software Development Kit (SDK): This Internal Evaluation License Agreement (this "Agreement") is entered into by and between MIPS and you (as an individual developer or a legal entity -- identified below as “Recipient”). MIPS shall make the Evaluation Software available to Recipient as described in accordance with the terms and conditions set forth below.
2026-09-26T19:28:52.3252916Z 
2026-09-26T19:28:52.3254850Z By clicking on the “Accept” button, downloading, installing, or otherwise using the Evaluation Materials (defined below), you agree to be bound by the terms of this Agreement effective as of the date you click “Accept” (the “Effective Date”), and if doing so on behalf of an entity, you represent that you are authorized to bind the entity to the terms and conditions of this Agreement. If you do not agree to be bound by the terms and conditions of this Agreement, do not download, install, or use the Evaluation Materials.
2026-09-26T19:28:52.3256568Z 
2026-09-26T19:28:52.3256737Z 1. DEFINITIONS. These terms shall have the following meanings:
2026-09-26T19:28:52.3256997Z 
2026-09-26T19:28:52.3257562Z 1.1 “MIPS” shall mean MIPS Technologies, Inc., a Delaware corporation having a principal place of business at: 955 East Arques Ave., Sunnyvale, CA 94085
2026-09-26T19:28:52.3258122Z 
2026-09-26T19:28:52.3258709Z 1.2 “Evaluation Software” shall mean MIPS Android™ emulator system images for Android Software Development Kit (SDK), as made available to Recipient.
2026-09-26T19:28:52.3259267Z 
2026-09-26T19:28:52.3260716Z 1.3 “Evaluation Materials" means, collectively, the Evaluation Software (in source and/or object code form) and documentation (including, without limitation, any design documents, specifications, reference manuals, and other related materials) related to the Evaluation Software as made available to Recipient.
2026-09-26T19:28:52.3261813Z 
2026-09-26T19:28:52.3265581Z 1.4 “Open Source Software” means any software that requires (as a condition of use, modification and/or distribution of such software) that such software or other software incorporated into, derived from or distributed with such software (a) be disclosed or distributed in source code form; or (b) be licensed by the user to third parties for the purpose of making and/or distributing derivative works; or (c) be redistributable at no charge. Open Source Software includes, without limitation, software licensed or distributed under any of the following licenses or distribution models, or licenses or distribution models substantially similar to any of the following: (a) GNU’s General Public License (GPL) or Lesser/Library GPL (LGPL), (b) the Artistic License (e.g., PERL), (c) the Mozilla Public License, (d) the Netscape Public License, (e) the Sun Community Source License (SCSL), (f) the Sun Industry Source License (SISL), (g) the Apache Software license and (h) the Common Public License (CPL).
2026-09-26T19:28:52.3268852Z 
2026-09-26T19:28:52.3270431Z 1.5 “Pre-Release Materials” means “alpha” or “beta” designated pre-release features, which may not be fully functional, which MIPS may substantially modify in producing any production version of the Evaluation Materials, and/or which is still under development by MIPS and/or MIPS’ suppliers.
2026-09-26T19:28:52.3271582Z 
2026-09-26T19:28:52.3273423Z 2. PURPOSE. MIPS desires to make the Evaluation Materials available to Recipient solely for Recipient's internal evaluation of the Evaluation Software to evaluate the desirability of cooperating with MIPS in developing products that are compatible with the Evaluation Software and/or to advise MIPS as to possible modifications to the Evaluation Software. Recipient may not disclose, distribute, modify (except to facilitate the above-mentioned internal evaluation), or make commercial use of the Evaluation Materials or any modifications of the Evaluation Materials.
2026-09-26T19:28:52.3275344Z 
2026-09-26T19:28:52.3276725Z THE EVALUATION MATERIALS ARE PROVIDED FOR EVALUATION PURPOSES ONLY AND MAY NOT BE MODIFIED (EXCEPT TO FACILITATE THE INTERNAL EVALUATION) OR DISTRIBUTED BY RECIPIENT OR INCORPORATED INTO RECIPIENT’S PRODUCTS OR SOFTWARE. PLEASE CONTACT A MIPS SALES REPRESENTATIVE TO LEARN ABOUT THE AVAILABILITY AND COST OF A COMMERCIAL VERSION OF THE EVALUATION SOFTWARE.
2026-09-26T19:28:52.3279029Z 
2026-09-26T19:28:52.3281149Z 3. TITLE. Title to the Evaluation Materials remains with MIPS or its suppliers. Recipient shall not mortgage, pledge or encumber the Evaluation Materials in any way. Recipient shall return all Evaluation Materials, keeping no copies, upon termination or expiration of this Agreement.
2026-09-26T19:28:52.3282888Z 
2026-09-26T19:28:52.3288317Z 4. LICENSE. MIPS grants Recipient a royalty-free, personal, nontransferable, nonexclusive license under its copyrights to use the Evaluation Software only for the purposes described in paragraph 2 above and only for a period beginning on the Effective Date and extending to the first anniversary of the Effective Date (the “Evaluation Period”). Unless otherwise communicated in writing by MIPS to Recipient, to the extent the Evaluation Software is provided in more than one delivery or release (each, a “Release”) the license grant in this Section 4 and the Evaluation Period shall apply to each Release, in which case the Evaluation Period shall begin on the date that the Release is made generally available and continue to the first anniversary of such date. Recipient may not make modifications to the Evaluation Software. Recipient shall not disassemble, reverse-engineer, or decompile any software that is not provided to Recipient in source code form.
2026-09-26T19:28:52.3292717Z 
2026-09-26T19:28:52.3292723Z 
2026-09-26T19:28:52.3293819Z EXCEPT AS PROVIDED HEREIN, NO OTHER LICENSE, EXPRESS OR IMPLIED, BY ESTOPPEL OR OTHERWISE, TO ANY OTHER MIPS INTELLECTUAL PROPERTY RIGHTS IS GRANTED TO THE RECIPIENT. OTHER THAN AS EXPLICITLY SET FORTH IN PARAGRAPH 2 ABOVE, NO RIGHT TO COPY, TO REPRODUCE, TO MODIFY, OR TO CREATE DERIVATIVE WORKS OF, THE EVALUATION MATERIALS IS GRANTED HEREIN.
2026-09-26T19:28:52.3294991Z 
2026-09-26T19:28:52.3295803Z 5. NO OBLIGATION. Recipient shall have no duty to purchase or license any product from MIPS. MIPS and its suppliers shall have no obligation to provide support for, or develop a non-evaluation version of, the Evaluation Software or to license any version of it.
2026-09-26T19:28:52.3296690Z 
2026-09-26T19:28:52.3301468Z 6. MODIFICATIONS. This Agreement does not obligate Recipient to provide MIPS with comments or suggestions regarding Evaluation Materials. However, should Recipient provide MIPS with comments or suggestions for the modification, correction, improvement or enhancement of (a) the Evaluation Materials or (b) MIPS products or processes which may embody the Evaluation Materials, then Recipient agrees to grant and hereby grants to MIPS a non-exclusive, irrevocable, worldwide, fully paid-up, royalty-free license, with the right to sublicense MIPS’ licensees and customers, under Recipient’s Intellectual property rights, to use and disclose such comments and suggestions in any manner MIPS chooses and to display, perform, copy, make, have made, use, sell, offer to sell, import, and otherwise dispose of MIPS’ and its sublicensee’s products embodying such comments and suggestions in any manner and via any media MIPS chooses, without reference to the source.
2026-09-26T19:28:52.3304842Z 
2026-09-26T19:28:52.3306537Z 7. WARRANTY DISCLAIMER. MIPS AND ITS SUPPLIERS MAKE NO WARRANTIES WITH RESPECT TO EVALUATION MATERIALS, EITHER EXPRESS OR IMPLIED, INCLUDING ANY IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE, OR ANY IMPLIED WARRANTY OF NONINFRINGEMENT WITH RESPECT TO THIRD PARTY INTELLECTUAL PROPERTY. RECIPIENT ACKNOWLEDGES AND AGREES THAT THE EVALUATION MATERIALS ARE PROVIDED “AS IS,” WITHOUT WARRANTY OF ANY KIND.
2026-09-26T19:28:52.3308020Z 
2026-09-26T19:28:52.3310730Z 8. LIMITATION OF LIABILITY. MIPS AND ITS SUPPLIERS SHALL NOT BE LIABLE FOR ANY PROPERTY DAMAGE, PERSONAL INJURY, LOSS OF PROFITS, INTERRUPTION OF BUSINESS OR FOR ANY DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL OR INCIDENTAL DAMAGES, HOWEVER CAUSED OR ALLEGED, WHETHER FOR BREACH OF WARRANTY, CONTRACT, STRICT LIABILITY OR OTHERWISE, INCLUDING WITHOUT LIMITATION, UNDER TORT OR OTHER LEGAL THEORY. MIPS AND ITS SUPPLIERS DISCLAIM ANY AND ALL LIABILITY, INCLUDING LIABILITY FOR INFRINGEMENT OF ANY INTELLECTUAL PROPERTY RIGHTS OF ANY KIND RELATING TO THE EVALUATION MATERIALS.
2026-09-26T19:28:52.3312988Z 
2026-09-26T19:28:52.3313772Z 9. EXPIRATION. MIPS may terminate this Agreement immediately after a breach by Recipient or otherwise at MIPS’ reasonable discretion and upon five (5) business days’ notice to Recipient.
2026-09-26T19:28:52.3314497Z 
2026-09-26T19:28:52.3314580Z 10. GENERAL.
2026-09-26T19:28:52.3314714Z 
2026-09-26T19:28:52.3317959Z 10.1 Controlling Law. This Agreement shall be governed by California law excluding its choice of law rules. With the exception of MIPS’ rights to enforce its intellectual property rights and any confidentiality obligations under this Agreement or any licenses distributed with the Evaluation Materials, all disputes and any claims arising under or relating to this Agreement shall be subject to the exclusive jurisdiction and venue of the state and federal courts located in Santa Clara County, California. Each party hereby agrees to jurisdiction and venue in the courts set forth in the preceding sentence. The parties agree that the United Nations Convention on Contracts for the International Sale of Goods is specifically excluded from application to this Agreement. The parties consent to the personal jurisdiction of the above courts.
2026-09-26T19:28:52.3321326Z 
2026-09-26T19:28:52.3322984Z 10.2 Remedies. Recipient acknowledges and agrees that any breach of confidentiality obligations under this Agreement or any licenses distributed with the Evaluation Materials, as well as any disclosure, commercialization, or public use of the Evaluation Materials, would cause irreparable injury to MIPS, and therefore Recipient agrees to consent to, and hereby consents to, the grant of an injunction by any court of competent jurisdiction in the event of an actual or threatened breach.
2026-09-26T19:28:52.3324711Z 
2026-09-26T19:28:52.3326740Z 10.3 Assignment. Recipient may not delegate, assign or transfer this Agreement, the license granted or any of Recipient’s rights, obligations, or duties hereunder, expressly, by implication, by operation of law, by way of merger (regardless of whether Recipient is the surviving entity) or acquisition, or otherwise and any attempt to do so, without MIPS’ express prior written consent, shall be ineffective, null and void. MIPS may freely assign this Agreement, and its rights and obligations hereunder, in its sole discretion.
2026-09-26T19:28:52.3328527Z 
2026-09-26T19:28:52.3330413Z 10.4 Entire Agreement. This Agreement constitutes the entire agreement between Recipient and MIPS and supersedes in their entirety any and all oral or written agreements previously existing between Recipient and MIPS with respect to the subject matter hereof. This Agreement may only be amended or supplemented by a writing that refers explicitly to this Agreement and that is signed or otherwise accepted by duly authorized representatives of Recipient and MIPS.
2026-09-26T19:28:52.3332129Z 
2026-09-26T19:28:52.3334155Z 10.5 Severability. In the event that any provision of this Agreement is finally adjudicated to be unenforceable or invalid under any applicable law, such unenforceability or invalidity shall not render this Agreement unenforceable or invalid as a whole, and, in such event, such unenforceable or invalid provision shall be interpreted so as to best accomplish the objectives of such provision within the limits of applicable law or applicable court decisions.
2026-09-26T19:28:52.3336581Z 
2026-09-26T19:28:52.3341647Z 10.6 Export Regulations / Export Control. Recipient shall not export, either directly or indirectly, any product, service or technical data or system incorporating the Evaluation Materials without first obtaining any required license or other necessary approval from the U.S. Department of Commerce or any other governing agency or department of the United States Government. In the event any product is exported from the United States or re-exported from a foreign destination by Recipient, Recipient shall ensure that the distribution and export/re-export or import of the product is in compliance with all applicable laws, regulations, orders, or other restrictions of the U.S. Export Administration Regulations and the appropriate foreign government. Recipient agrees that neither it nor any of its subsidiaries will export/re-export any technical data, process, product, or service, directly or indirectly, to any country for which the United States government or any agency thereof or the foreign government from where it is shipping requires an export license, or other governmental approval, without first obtaining such license or approval. Recipient also agrees to implement measures to ensure that foreign national employees are authorized to receive any information controlled by U.S. export control laws. An export is "deemed" to take place when information is released to a foreign national wherever located.
2026-09-26T19:28:52.3348482Z 
2026-09-26T19:28:52.3352518Z 10.7 Special Terms for Pre-Release Materials. If so indicated in the description of the Evaluation Software, the Evaluation Software may contain Pre-Release Materials. Recipient hereby understands, acknowledges and agrees that: (i) Pre-Release Materials may not be fully tested and may contain bugs or errors; (ii) Pre-Release materials are not suitable for commercial release in their current state; (iii) regulatory approvals for Pre-Release Materials (such as UL or FCC) have not been obtained, and Pre-Release Materials may therefore not be certified for use in certain countries or environments or may not be suitable for certain applications and (iv) MIPS can provide no assurance that it will ever produce or make generally available a production version of the Pre-Release Materials . MIPS is not under any obligation to develop and/or release or offer for sale or license a final product based upon the Pre-Release Materials and may unilaterally elect to abandon the Pre-Release Materials or any such development platform at any time and without any obligation or liability whatsoever to Recipient or any other person.
2026-09-26T19:28:52.3356514Z 
2026-09-26T19:28:52.3357245Z ANY PRE-RELEASE MATERIALS ARE NON-QUALIFIED AND, AS SUCH, ARE PROVIDED “AS IS” AND “AS AVAILABLE”, POSSIBLY WITH FAULTS, AND WITHOUT REPRESENTATION OR WARRANTY OF ANY KIND.
2026-09-26T19:28:52.3357877Z 
2026-09-26T19:28:52.3361294Z 10.8 Open Source Software. In the event Open Source software is included with Evaluation Software, such Open Source software is licensed pursuant to the applicable Open Source software license agreement identified in the Open Source software comments in the applicable source code file(s) and/or file header as indicated in the Evaluation Software. Additional detail may be available (where applicable) in the accompanying on-line documentation. With respect to the Open Source software, nothing in this Agreement limits any rights under, or grants rights that supersede, the terms of any applicable Open Source software license agreement.
2026-09-26T19:28:52.3364419Z ---------------------------------------
2026-09-26T19:28:52.3364749Z Accept? (y/N): All SDK package licenses accepted
2026-09-26T19:28:52.3364962Z 
2026-09-26T19:28:52.3365236Z [command]/usr/local/lib/android/sdk/cmdline-tools/22.0/bin/sdkmanager platform-tools
2026-09-26T19:28:52.4094803Z WARNING: The SDK Manager CLI tool (sdkmanager) is deprecated. Use Android CLI instead.
2026-09-26T19:28:52.4096554Z The 'android' binary can also be found in the cmdline-tools directory, and 'android sdk' is the replacement for 'sdkmanager'.
2026-09-26T19:28:52.4098192Z To learn more about the Android CLI and how to use it, see the documentation (https://d.android.com/tools/agents/android-cli)
2026-09-26T19:28:52.4099618Z 
2026-09-26T19:28:53.1823881Z Loading package information...                                                  
2026-09-26T19:28:53.2720712Z Loading local repository...                                                     
2026-09-26T19:28:53.2721466Z [                                       ] 3% Loading local repository...        
2026-09-26T19:28:53.2806940Z [                                       ] 3% Fetch remote repository...         
2026-09-26T19:28:53.6344844Z [=                                      ] 3% Fetch remote repository...         
2026-09-26T19:28:53.7064056Z [=                                      ] 4% Fetch remote repository...         
2026-09-26T19:28:53.7552435Z [=                                      ] 5% Fetch remote repository...         
2026-09-26T19:28:53.8020155Z [==                                     ] 5% Fetch remote repository...         
2026-09-26T19:28:53.8785015Z [==                                     ] 6% Fetch remote repository...         
2026-09-26T19:28:53.9650674Z [==                                     ] 7% Fetch remote repository...         
2026-09-26T19:28:53.9680102Z [==                                     ] 7% Computing updates...               
2026-09-26T19:28:53.9797954Z [===                                    ] 8% Computing updates...               
2026-09-26T19:28:53.9874196Z [===                                    ] 10% Computing updates...              
2026-09-26T19:28:53.9880674Z [=======================================] 100% Computing updates...             
2026-09-26T19:28:53.9882272Z 
2026-09-26T19:28:54.0243345Z ##[group]Run yes | sdkmanager --licenses > /dev/null || true
2026-09-26T19:28:54.0243791Z [36;1myes | sdkmanager --licenses > /dev/null || true[0m
2026-09-26T19:28:54.0305785Z shell: /usr/bin/bash -e {0}
2026-09-26T19:28:54.0306034Z env:
2026-09-26T19:28:54.0306338Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:28:54.0306845Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:28:54.0307253Z   ANDROID_HOME: /usr/local/lib/android/sdk
2026-09-26T19:28:54.0307549Z   ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
2026-09-26T19:28:54.0307820Z ##[endgroup]
2026-09-26T19:28:54.1311233Z WARNING: The SDK Manager CLI tool (sdkmanager) is deprecated. Use Android CLI instead.
2026-09-26T19:28:54.1331678Z The 'android' binary can also be found in the cmdline-tools directory, and 'android sdk' is the replacement for 'sdkmanager'.
2026-09-26T19:28:54.1334415Z To learn more about the Android CLI and how to use it, see the documentation (https://d.android.com/tools/agents/android-cli)
2026-09-26T19:28:54.1335364Z 
2026-09-26T19:28:55.8171718Z yes: standard output: Broken pipe
2026-09-26T19:28:55.8220742Z ##[group]Run sdkmanager "ndk;30.0.16248370"
2026-09-26T19:28:55.8221110Z [36;1msdkmanager "ndk;30.0.16248370"[0m
2026-09-26T19:28:55.8282770Z shell: /usr/bin/bash -e {0}
2026-09-26T19:28:55.8283010Z env:
2026-09-26T19:28:55.8283317Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:28:55.8283819Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:28:55.8284209Z   ANDROID_HOME: /usr/local/lib/android/sdk
2026-09-26T19:28:55.8284729Z   ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
2026-09-26T19:28:55.8284999Z ##[endgroup]
2026-09-26T19:28:55.9298634Z WARNING: The SDK Manager CLI tool (sdkmanager) is deprecated. Use Android CLI instead.
2026-09-26T19:28:55.9300651Z The 'android' binary can also be found in the cmdline-tools directory, and 'android sdk' is the replacement for 'sdkmanager'.
2026-09-26T19:28:55.9302655Z To learn more about the Android CLI and how to use it, see the documentation (https://d.android.com/tools/agents/android-cli)
2026-09-26T19:28:55.9303761Z 
2026-09-26T19:28:56.7203380Z Loading package information...                                                  
2026-09-26T19:28:56.8323346Z Loading local repository...                                                     
2026-09-26T19:28:56.8324159Z [                                       ] 3% Loading local repository...        
2026-09-26T19:28:56.8412762Z [                                       ] 3% Fetch remote repository...         
2026-09-26T19:28:57.1301659Z [=                                      ] 3% Fetch remote repository...         
2026-09-26T19:28:57.1741447Z [=                                      ] 4% Fetch remote repository...         
2026-09-26T19:28:57.2030941Z [=                                      ] 5% Fetch remote repository...         
2026-09-26T19:28:57.2407033Z [==                                     ] 5% Fetch remote repository...         
2026-09-26T19:28:57.2982717Z [==                                     ] 6% Fetch remote repository...         
2026-09-26T19:28:57.3510875Z [==                                     ] 7% Fetch remote repository...         
2026-09-26T19:28:57.3540346Z [==                                     ] 7% Computing updates...               
2026-09-26T19:28:57.3665566Z [===                                    ] 8% Computing updates...               
2026-09-26T19:28:57.3800610Z [===                                    ] 10% Computing updates...              
2026-09-26T19:28:57.6991120Z [===                                    ] 10% Installing NDK (Side by side) 30.0
2026-09-26T19:28:58.1379061Z [===                                    ] 10% Downloading android-ndk-r30-linux.
2026-09-26T19:28:58.3759294Z [====                                   ] 10% Downloading android-ndk-r30-linux.
2026-09-26T19:28:58.5425960Z [====                                   ] 11% Downloading android-ndk-r30-linux.
2026-09-26T19:28:58.7080104Z [====                                   ] 12% Downloading android-ndk-r30-linux.
2026-09-26T19:28:58.8110502Z [=====                                  ] 13% Downloading android-ndk-r30-linux.
2026-09-26T19:28:58.8811996Z [=====                                  ] 14% Downloading android-ndk-r30-linux.
2026-09-26T19:28:58.9254001Z [=====                                  ] 15% Downloading android-ndk-r30-linux.
2026-09-26T19:28:58.9548024Z [======                                 ] 15% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.0146425Z [======                                 ] 16% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.0877449Z [======                                 ] 17% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.1469916Z [=======                                ] 18% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.2201512Z [=======                                ] 19% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.2504728Z [=======                                ] 20% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.2799859Z [========                               ] 20% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.3537923Z [========                               ] 21% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.4136335Z [========                               ] 22% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.4864183Z [=========                              ] 23% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.5763305Z [=========                              ] 24% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.6163031Z [=========                              ] 25% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.6518708Z [==========                             ] 25% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.7410682Z [==========                             ] 26% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.8124555Z [==========                             ] 27% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.9002869Z [===========                            ] 28% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.9596460Z [===========                            ] 29% Downloading android-ndk-r30-linux.
2026-09-26T19:28:59.9883585Z [===========                            ] 30% Downloading android-ndk-r30-linux.
2026-09-26T19:29:00.0320510Z [============                           ] 30% Downloading android-ndk-r30-linux.
2026-09-26T19:29:00.0897515Z [============                           ] 31% Downloading android-ndk-r30-linux.
2026-09-26T19:29:00.1480036Z [============                           ] 32% Downloading android-ndk-r30-linux.
2026-09-26T19:29:00.1487099Z [============                           ] 33% Downloading android-ndk-r30-linux.
2026-09-26T19:29:00.5474171Z [============                           ] 33% Unzipping...                      
2026-09-26T19:29:00.5475374Z [============                           ] 33% Unzipping... android-ndk-r30/     
2026-09-26T19:29:00.5510711Z [============                           ] 33% Unzipping... android-ndk-r30/wrap.
2026-09-26T19:29:00.5515596Z [=============                          ] 33% Unzipping... android-ndk-r30/wrap.
2026-09-26T19:29:00.5516853Z [=============                          ] 33% Unzipping... android-ndk-r30/READM
2026-09-26T19:29:00.5558381Z [=============                          ] 33% Unzipping... android-ndk-r30/ndk-b
2026-09-26T19:29:00.5618667Z [=============                          ] 33% Unzipping... android-ndk-r30/NOTIC
2026-09-26T19:29:01.0754480Z [=============                          ] 33% Unzipping... android-ndk-r30/simpl
2026-09-26T19:29:01.0825394Z [=============                          ] 34% Unzipping... android-ndk-r30/simpl
2026-09-26T19:29:01.0868768Z [=============                          ] 34% Unzipping... android-ndk-r30/meta/
2026-09-26T19:29:01.0869671Z [=============                          ] 34% Unzipping... android-ndk-r30/CHANG
2026-09-26T19:29:01.1701037Z [=============                          ] 34% Unzipping... android-ndk-r30/shade
2026-09-26T19:29:01.4665987Z [=============                          ] 34% Unzipping... android-ndk-r30/sourc
2026-09-26T19:29:01.7897212Z [=============                          ] 34% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:02.4828213Z [=============                          ] 35% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:02.7180550Z [==============                         ] 35% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:03.1964696Z [==============                         ] 36% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:03.6540005Z [==============                         ] 37% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:04.1543695Z [===============                        ] 38% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:04.7105917Z [===============                        ] 39% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:04.9040050Z [===============                        ] 40% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:05.0927950Z [================                       ] 40% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:05.5080157Z [================                       ] 41% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:05.9212668Z [================                       ] 42% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:06.3681062Z [=================                      ] 43% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:06.7944019Z [=================                      ] 44% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:06.9776007Z [=================                      ] 45% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:07.2165318Z [==================                     ] 45% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:07.6084819Z [==================                     ] 46% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:08.0540448Z [==================                     ] 47% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:08.5380284Z [===================                    ] 48% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:08.8926799Z [===================                    ] 49% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:09.1955016Z [===================                    ] 50% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:09.3473368Z [====================                   ] 50% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:09.8031534Z [====================                   ] 51% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:10.2722404Z [====================                   ] 52% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:10.7694452Z [=====================                  ] 53% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:11.1953515Z [=====================                  ] 54% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:11.3560121Z [=====================                  ] 55% Unzipping... android-ndk-r30/toolc
2026-09-26T19:29:11.3561087Z [=====================                  ] 55% Unzipping... android-ndk-r30/ndk-s
2026-09-26T19:29:11.3957605Z [=====================                  ] 55% Unzipping... android-ndk-r30/prebu
2026-09-26T19:29:11.3958838Z [=====================                  ] 55% Unzipping... android-ndk-r30/ndk-w
2026-09-26T19:29:11.3960848Z [=====================                  ] 55% Unzipping... android-ndk-r30/ndk-g
2026-09-26T19:29:11.4086114Z [=====================                  ] 55% Unzipping... android-ndk-r30/build
2026-09-26T19:29:11.4106972Z [=====================                  ] 55% Unzipping... android-ndk-r30/NOTIC
2026-09-26T19:29:11.4108530Z [=====================                  ] 55% Unzipping... android-ndk-r30/ndk-l
2026-09-26T19:29:11.5462750Z [=====================                  ] 55% Unzipping... android-ndk-r30/sourc
2026-09-26T19:29:11.5463372Z [=======================================] 100% Unzipping... android-ndk-r30/sour
2026-09-26T19:29:11.8870558Z 
2026-09-26T19:29:11.9282620Z ##[group]Run gradle/actions/setup-gradle@v4
2026-09-26T19:29:11.9282931Z with:
2026-09-26T19:29:11.9283128Z   gradle-version: 8.14.3
2026-09-26T19:29:11.9283396Z   cache-disabled: false
2026-09-26T19:29:11.9283621Z   cache-read-only: false
2026-09-26T19:29:11.9283844Z   cache-write-only: false
2026-09-26T19:29:11.9284083Z   cache-overwrite-existing: false
2026-09-26T19:29:11.9284340Z   cache-cleanup: on-success
2026-09-26T19:29:11.9284623Z   gradle-home-cache-includes: caches
notifications

2026-09-26T19:29:11.9284940Z   add-job-summary: always
2026-09-26T19:29:11.9285191Z   add-job-summary-as-pr-comment: never
2026-09-26T19:29:11.9285479Z   dependency-graph: disabled
2026-09-26T19:29:11.9285780Z   dependency-graph-report-dir: dependency-graph-reports
2026-09-26T19:29:11.9286137Z   dependency-graph-continue-on-failure: true
2026-09-26T19:29:11.9286443Z   build-scan-publish: false
2026-09-26T19:29:11.9286680Z   validate-wrappers: true
2026-09-26T19:29:11.9286946Z   allow-snapshot-wrappers: false
2026-09-26T19:29:11.9287219Z   gradle-home-cache-strict-match: false
2026-09-26T19:29:11.9287500Z   workflow-job-context: null
2026-09-26T19:29:11.9290584Z   github-token: ***
2026-09-26T19:29:11.9290839Z env:
2026-09-26T19:29:11.9291147Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:29:11.9291643Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:29:11.9292042Z   ANDROID_HOME: /usr/local/lib/android/sdk
2026-09-26T19:29:11.9292358Z   ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
2026-09-26T19:29:11.9292637Z ##[endgroup]
2026-09-26T19:29:12.2580713Z Merged default JDK locations into /home/runner/.m2/toolchains.xml
2026-09-26T19:29:12.2600535Z Preparing cache for cleanup.
2026-09-26T19:29:12.2620915Z ##[group]Restore Gradle state from cache
2026-09-26T19:29:12.3451145Z Cache hit for restore-key: gradle-home-v1|Linux-X64|build-and-test[5df42585ae8f8da228c604b8b4e8707c]-33651a617b1430ddf782fa7f5870de323be77c69
2026-09-26T19:29:12.6439164Z Received 28635320 of 28635320 (100.0%), 107.9 MBs/sec
2026-09-26T19:29:12.6442039Z Cache Size: ~27 MB (28635320 B)
2026-09-26T19:29:12.6473468Z [command]/usr/bin/tar -xf /home/runner/work/_temp/d9fc734c-ab16-4126-8ecd-bcb348a0cee8/cache.tzst -P -C /home/runner/work/Client/Client --use-compress-program unzstd
2026-09-26T19:29:12.8462983Z Cache restored successfully
2026-09-26T19:29:12.8480439Z Restored cache entry with key gradle-home-v1|Linux-X64|build-and-test[5df42585ae8f8da228c604b8b4e8707c]-0be23825eaed73155ace9db7030d6ba49600ec9e to /home/runner/.gradle/caches,/home/runner/.gradle/notifications,/home/runner/.gradle/.setup-gradle in 587ms
2026-09-26T19:29:12.9352888Z Cache hit for: gradle-generated-gradle-jars-v1-4434c59050703a48bf14168622b59891
2026-09-26T19:29:12.9377488Z Cache hit for: gradle-wrapper-zips-v1-5ae5d3da5e8e0c748d04bcdd9bc97bdb
2026-09-26T19:29:12.9399757Z Cache hit for: gradle-transforms-v1-f4f0db03872cde0a0bf4253af892f532
2026-09-26T19:29:12.9420122Z Cache hit for: gradle-groovy-dsl-v1-1e05371d6921ba3d4c9bc8e1f110feb7
2026-09-26T19:29:12.9442446Z Cache hit for: gradle-instrumented-jars-v1-668e5ea1387707e4010997fa6a52e643
2026-09-26T19:29:12.9473882Z Cache hit for: gradle-kotlin-dsl-v1-f217b3729e169163e06723c7b19ebec0
2026-09-26T19:29:12.9664839Z Cache hit for: gradle-dependencies-v1-59af8e349761667e89dff02d77ac12cb
2026-09-26T19:29:13.1723095Z Received 76983 of 76983 (100.0%), 0.5 MBs/sec
2026-09-26T19:29:13.1723662Z Cache Size: ~0 MB (76983 B)
2026-09-26T19:29:13.2978614Z Received 130228 of 130228 (100.0%), 0.6 MBs/sec
2026-09-26T19:29:13.2979799Z Cache Size: ~0 MB (130228 B)
2026-09-26T19:29:13.2983995Z Received 118767 of 118767 (100.0%), 0.5 MBs/sec
2026-09-26T19:29:13.2984830Z Cache Size: ~0 MB (118767 B)
2026-09-26T19:29:13.5258994Z [command]/usr/bin/tar -xf /home/runner/work/_temp/091f17f2-71de-49e5-b519-fd0d1b999015/cache.tzst -P -C /home/runner/work/Client/Client --use-compress-program unzstd
2026-09-26T19:29:13.5804970Z Cache restored successfully
2026-09-26T19:29:13.5822185Z Restored cache entry with key gradle-instrumented-jars-v1-668e5ea1387707e4010997fa6a52e643 to /home/runner/.gradle/caches/jars-*/*/ in 732ms
2026-09-26T19:29:13.6277523Z [command]/usr/bin/tar -xf /home/runner/work/_temp/497f3391-a399-4a61-9a64-e1b7ed680418/cache.tzst -P -C /home/runner/work/Client/Client --use-compress-program unzstd
2026-09-26T19:29:13.6325622Z [command]/usr/bin/tar -xf /home/runner/work/_temp/55795b8d-0497-4c60-a253-5f68a970aa78/cache.tzst -P -C /home/runner/work/Client/Client --use-compress-program unzstd
2026-09-26T19:29:13.6971108Z Cache restored successfully
2026-09-26T19:29:13.7247724Z Cache restored successfully
2026-09-26T19:29:13.7397915Z Restored cache entry with key gradle-groovy-dsl-v1-1e05371d6921ba3d4c9bc8e1f110feb7 to /home/runner/.gradle/caches/*/groovy-dsl/*/ in 889ms
2026-09-26T19:29:13.7400469Z Restored cache entry with key gradle-kotlin-dsl-v1-f217b3729e169163e06723c7b19ebec0 to /home/runner/.gradle/caches/*/kotlin-dsl/accessors/*/,/home/runner/.gradle/caches/*/kotlin-dsl/scripts/*/ in 889ms
2026-09-26T19:29:13.9465363Z Received 41093005 of 41093005 (100.0%), 39.6 MBs/sec
2026-09-26T19:29:13.9467536Z Cache Size: ~39 MB (41093005 B)
2026-09-26T19:29:14.0022238Z Received 134217728 of 247380823 (54.3%), 123.7 MBs/sec
2026-09-26T19:29:14.0093308Z Received 20971520 of 136702325 (15.3%), 20.0 MBs/sec
2026-09-26T19:29:14.1141576Z Received 29360128 of 498019069 (5.9%), 27.7 MBs/sec
2026-09-26T19:29:14.1143702Z [command]/usr/bin/tar -xf /home/runner/work/_temp/8a10ee77-9e2f-42ef-bb8c-070199da5ce1/cache.tzst -P -C /home/runner/work/Client/Client --use-compress-program unzstd
2026-09-26T19:29:14.5777364Z Cache restored successfully
2026-09-26T19:29:14.5964344Z Restored cache entry with key gradle-generated-gradle-jars-v1-4434c59050703a48bf14168622b59891 to /home/runner/.gradle/caches/8.14.3/generated-gradle-jars/gradle-api-8.14.3.jar in 1748ms
2026-09-26T19:29:14.7239137Z Received 247380823 of 247380823 (100.0%), 134.3 MBs/sec
2026-09-26T19:29:14.7242940Z Cache Size: ~236 MB (247380823 B)
2026-09-26T19:29:14.8397588Z [command]/usr/bin/tar -xf /home/runner/work/_temp/cf927281-ddd5-4885-9eec-8b3e0d028dae/cache.tzst -P -C /home/runner/work/Client/Client --use-compress-program unzstd
2026-09-26T19:29:15.0116196Z Received 134217728 of 136702325 (98.2%), 63.9 MBs/sec
2026-09-26T19:29:15.0818264Z Received 136702325 of 136702325 (100.0%), 62.9 MBs/sec
2026-09-26T19:29:15.0819695Z Cache Size: ~130 MB (136702325 B)
2026-09-26T19:29:15.0849804Z [command]/usr/bin/tar -xf /home/runner/work/_temp/83320ab5-5306-40d9-a196-6fcd979b6092/cache.tzst -P -C /home/runner/work/Client/Client --use-compress-program unzstd
2026-09-26T19:29:15.1158767Z Received 134217728 of 498019069 (27.0%), 63.6 MBs/sec
2026-09-26T19:29:15.4721004Z Cache restored successfully
2026-09-26T19:29:15.4896977Z Restored cache entry with key gradle-wrapper-zips-v1-5ae5d3da5e8e0c748d04bcdd9bc97bdb to /home/runner/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66 in 2640ms
2026-09-26T19:29:16.1200924Z Received 390070272 of 498019069 (78.3%), 123.4 MBs/sec
2026-09-26T19:29:16.6819701Z Received 498019069 of 498019069 (100.0%), 132.7 MBs/sec
2026-09-26T19:29:16.6822843Z Cache Size: ~475 MB (498019069 B)
2026-09-26T19:29:16.7061519Z [command]/usr/bin/tar -xf /home/runner/work/_temp/6d8f57b5-5d20-49c5-b14c-2d2c975f5fbb/cache.tzst -P -C /home/runner/work/Client/Client --use-compress-program unzstd
2026-09-26T19:29:17.6120903Z Cache restored successfully
2026-09-26T19:29:17.6260891Z Restored cache entry with key gradle-transforms-v1-f4f0db03872cde0a0bf4253af892f532 to /home/runner/.gradle/caches/transforms-4/*/,/home/runner/.gradle/caches/*/transforms/*/ in 4775ms
2026-09-26T19:29:17.7057035Z Cache restored successfully
2026-09-26T19:29:17.7268884Z Restored cache entry with key gradle-dependencies-v1-59af8e349761667e89dff02d77ac12cb to /home/runner/.gradle/caches/modules-*/files-*/*/*/*/* in 4877ms
2026-09-26T19:29:17.7652522Z ##[endgroup]
2026-09-26T19:29:17.7711308Z ##[group]All Gradle Wrapper jars are valid
2026-09-26T19:29:17.7712910Z 
2026-09-26T19:29:17.7713471Z ##[endgroup]
2026-09-26T19:29:17.8798950Z ##[group]Provision Gradle 8.14.3
2026-09-26T19:29:22.4876710Z Cache hit for: gradle-8.14.3
2026-09-26T19:29:23.0115094Z Received 137301072 of 137301072 (100.0%), 256.2 MBs/sec
2026-09-26T19:29:23.0117202Z Cache Size: ~131 MB (137301072 B)
2026-09-26T19:29:23.0151370Z [command]/usr/bin/tar -xf /home/runner/work/_temp/dd000178-2d96-4126-a5ce-860d9a42ad92/cache.tzst -P -C /home/runner/work/Client/Client --use-compress-program unzstd
2026-09-26T19:29:23.1857902Z Cache restored successfully
2026-09-26T19:29:23.1941064Z Restored Gradle distribution gradle-8.14.3 from cache to /home/runner/work/_temp/.gradle-actions/gradle-installations/downloads/gradle-8.14.3-bin.zip
2026-09-26T19:29:23.1962728Z [command]/usr/bin/unzip -o -q /home/runner/work/_temp/.gradle-actions/gradle-installations/downloads/gradle-8.14.3-bin.zip
2026-09-26T19:29:24.1676433Z Extracted Gradle 8.14.3 to /home/runner/work/_temp/.gradle-actions/gradle-installations/installs/gradle-8.14.3
2026-09-26T19:29:24.1678319Z Provisioned Gradle executable /home/runner/work/_temp/.gradle-actions/gradle-installations/installs/gradle-8.14.3/bin/gradle
2026-09-26T19:29:24.1680166Z ##[endgroup]
2026-09-26T19:29:24.1899937Z ##[group]Run set -euo pipefail
2026-09-26T19:29:24.1900618Z [36;1mset -euo pipefail[0m
2026-09-26T19:29:24.1901396Z [36;1mgradle wrapper --gradle-version 8.14.3 --distribution-type bin[0m
2026-09-26T19:29:24.1902280Z [36;1mtest -s gradle/wrapper/gradle-wrapper.jar[0m
2026-09-26T19:29:24.1902956Z [36;1mchmod +x gradlew[0m
2026-09-26T19:29:24.1973067Z shell: /usr/bin/bash -e {0}
2026-09-26T19:29:24.1973581Z env:
2026-09-26T19:29:24.1973944Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:29:24.1974520Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:29:24.1974978Z   ANDROID_HOME: /usr/local/lib/android/sdk
2026-09-26T19:29:24.1975325Z   ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
2026-09-26T19:29:24.1975698Z   GRADLE_ACTION_ID: gradle/actions/setup-gradle
2026-09-26T19:29:24.1976049Z   GRADLE_USER_HOME: /home/runner/.gradle
2026-09-26T19:29:24.1976414Z   GRADLE_BUILD_ACTION_SETUP_COMPLETED: true
2026-09-26T19:29:24.1976754Z   GRADLE_BUILD_ACTION_CACHE_RESTORED: true
2026-09-26T19:29:24.1977243Z   DEVELOCITY_INJECTION_INIT_SCRIPT_NAME: gradle-actions.inject-develocity.init.gradle
2026-09-26T19:29:24.1977770Z   DEVELOCITY_INJECTION_CUSTOM_VALUE: gradle-actions
2026-09-26T19:29:24.1978130Z   GITHUB_DEPENDENCY_GRAPH_ENABLED: false
2026-09-26T19:29:24.1978446Z ##[endgroup]
2026-09-26T19:29:25.5061218Z Starting a Gradle Daemon (subsequent builds will be faster)
2026-09-26T19:29:36.2064725Z > Task :wrapper
2026-09-26T19:29:36.2091253Z gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_4-1790450969078.json
2026-09-26T19:29:36.2115957Z 
2026-09-26T19:29:36.2139784Z BUILD SUCCESSFUL in 11s
2026-09-26T19:29:36.2140463Z 1 actionable task: 1 executed
2026-09-26T19:29:36.6057380Z ##[group]Run gradle/actions/wrapper-validation@v4
2026-09-26T19:29:36.6057788Z with:
2026-09-26T19:29:36.6058045Z   min-wrapper-count: 1
2026-09-26T19:29:36.6058333Z   allow-snapshots: false
2026-09-26T19:29:36.6058610Z env:
2026-09-26T19:29:36.6058962Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:29:36.6059852Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:29:36.6060322Z   ANDROID_HOME: /usr/local/lib/android/sdk
2026-09-26T19:29:36.6060673Z   ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
2026-09-26T19:29:36.6061066Z   GRADLE_ACTION_ID: gradle/actions/setup-gradle
2026-09-26T19:29:36.6061449Z   GRADLE_USER_HOME: /home/runner/.gradle
2026-09-26T19:29:36.6061789Z   GRADLE_BUILD_ACTION_SETUP_COMPLETED: true
2026-09-26T19:29:36.6062143Z   GRADLE_BUILD_ACTION_CACHE_RESTORED: true
2026-09-26T19:29:36.6062637Z   DEVELOCITY_INJECTION_INIT_SCRIPT_NAME: gradle-actions.inject-develocity.init.gradle
2026-09-26T19:29:36.6063212Z   DEVELOCITY_INJECTION_CUSTOM_VALUE: gradle-actions
2026-09-26T19:29:36.6063586Z   GITHUB_DEPENDENCY_GRAPH_ENABLED: false
2026-09-26T19:29:36.6063905Z ##[endgroup]
2026-09-26T19:29:36.7978611Z ✓ Found known Gradle Wrapper JAR files:
2026-09-26T19:29:36.7980403Z   7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172 gradle/wrapper/gradle-wrapper.jar
2026-09-26T19:29:36.8105634Z ##[group]Run ./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace --no-daemon --max-workers=2
2026-09-26T19:29:36.8107044Z [36;1m./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace --no-daemon --max-workers=2[0m
2026-09-26T19:29:36.8196966Z shell: /usr/bin/bash -e {0}
2026-09-26T19:29:36.8197483Z env:
2026-09-26T19:29:36.8198081Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:29:36.8198793Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-26T19:29:36.8199273Z   ANDROID_HOME: /usr/local/lib/android/sdk
2026-09-26T19:29:36.8199962Z   ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
2026-09-26T19:29:36.8200512Z   GRADLE_ACTION_ID: gradle/actions/wrapper-validation
2026-09-26T19:29:36.8201000Z   GRADLE_USER_HOME: /home/runner/.gradle
2026-09-26T19:29:36.8201375Z   GRADLE_BUILD_ACTION_SETUP_COMPLETED: true
2026-09-26T19:29:36.8201727Z   GRADLE_BUILD_ACTION_CACHE_RESTORED: true
2026-09-26T19:29:36.8202271Z   DEVELOCITY_INJECTION_INIT_SCRIPT_NAME: gradle-actions.inject-develocity.init.gradle
2026-09-26T19:29:36.8202810Z   DEVELOCITY_INJECTION_CUSTOM_VALUE: gradle-actions
2026-09-26T19:29:36.8203231Z   GITHUB_DEPENDENCY_GRAPH_ENABLED: false
2026-09-26T19:29:36.8204177Z ##[endgroup]
2026-09-26T19:29:37.5201887Z To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/8.14.3/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
2026-09-26T19:29:38.5165120Z Daemon will be stopped at the end of the build 
2026-09-26T19:29:47.5169210Z > Task :app:preBuild UP-TO-DATE
2026-09-26T19:29:47.5170530Z > Task :app:preDebugBuild UP-TO-DATE
2026-09-26T19:29:47.5171756Z > Task :app:checkKotlinGradlePluginConfigurationErrors SKIPPED
2026-09-26T19:29:47.6162425Z > Task :app:generateDebugBuildConfig FROM-CACHE
2026-09-26T19:29:48.2164437Z > Task :app:processDebugNavigationResources FROM-CACHE
2026-09-26T19:29:48.2210219Z > Task :app:checkDebugAarMetadata
2026-09-26T19:29:48.2236266Z > Task :app:compileDebugNavigationResources FROM-CACHE
2026-09-26T19:29:48.2256710Z > Task :app:generateDebugResValues FROM-CACHE
2026-09-26T19:29:48.3181192Z > Task :app:mapDebugSourceSetPaths
2026-09-26T19:29:48.3200717Z > Task :app:generateDebugResources FROM-CACHE
2026-09-26T19:29:48.4159106Z > Task :app:mergeDebugResources FROM-CACHE
2026-09-26T19:29:48.4160126Z > Task :app:packageDebugResources FROM-CACHE
2026-09-26T19:29:49.1188823Z > Task :app:parseDebugLocalResources FROM-CACHE
2026-09-26T19:29:49.1192862Z > Task :app:createDebugCompatibleScreenManifests
2026-09-26T19:29:49.1194015Z > Task :app:extractDeepLinksDebug FROM-CACHE
2026-09-26T19:29:49.2162328Z > Task :app:processDebugMainManifest FROM-CACHE
2026-09-26T19:29:49.2164113Z > Task :app:processDebugManifest FROM-CACHE
2026-09-26T19:29:49.2165287Z > Task :app:processDebugManifestForPackage FROM-CACHE
2026-09-26T19:29:49.4160856Z > Task :app:processDebugResources FROM-CACHE
2026-09-26T19:29:49.7167005Z > Task :app:javaPreCompileDebug FROM-CACHE
2026-09-26T19:29:49.7188873Z > Task :app:preDebugUnitTestBuild UP-TO-DATE
2026-09-26T19:29:49.7201848Z > Task :app:javaPreCompileDebugUnitTest FROM-CACHE
2026-09-26T19:29:49.7216491Z > Task :app:preDebugAndroidTestBuild SKIPPED
2026-09-26T19:29:49.7230378Z > Task :app:generateDebugAndroidTestResValues FROM-CACHE
2026-09-26T19:29:49.8189556Z > Task :app:extractProguardFiles
2026-09-26T19:29:49.8205370Z > Task :app:mergeDebugNativeDebugMetadata NO-SOURCE
2026-09-26T19:29:49.8230209Z > Task :app:mergeDebugShaders
2026-09-26T19:29:49.8260151Z > Task :app:compileDebugShaders NO-SOURCE
2026-09-26T19:29:49.8274957Z > Task :app:generateDebugAssets UP-TO-DATE
2026-09-26T19:29:49.9176226Z > Task :app:mergeDebugAssets
2026-09-26T19:29:50.0159975Z > Task :app:compressDebugAssets FROM-CACHE
2026-09-26T19:29:50.0204980Z > Task :app:desugarDebugFileDependencies FROM-CACHE
2026-09-26T19:29:50.2180909Z > Task :app:checkDebugDuplicateClasses
2026-09-26T19:29:50.9180754Z > Task :app:mergeExtDexDebug FROM-CACHE
2026-09-26T19:29:50.9181618Z > Task :app:mergeLibDexDebug FROM-CACHE
2026-09-26T19:29:52.9254347Z 
2026-09-26T19:29:52.9482168Z > Task :app:configureCMakeDebug[arm64-v8a]
2026-09-26T19:29:52.9561559Z [CXX5304] This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered. This can happen if you use versions of Android Studio and the command-line tools that were released at different times.
2026-09-26T19:29:52.9711618Z [CXX5304] This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered. This can happen if you use versions of Android Studio and the command-line tools that were released at different times.
2026-09-26T19:29:53.9160906Z Checking the license for package CMake 3.22.1 in /usr/local/lib/android/sdk/licenses
2026-09-26T19:29:53.9162344Z License for package CMake 3.22.1 accepted.
2026-09-26T19:29:53.9163321Z Preparing "Install CMake 3.22.1 v.3.22.1".
2026-09-26T19:29:55.8190771Z "Install CMake 3.22.1 v.3.22.1" ready.
2026-09-26T19:29:55.8220682Z Installing CMake 3.22.1 in /usr/local/lib/android/sdk/cmake/3.22.1
2026-09-26T19:29:55.8230142Z "Install CMake 3.22.1 v.3.22.1" complete.
2026-09-26T19:29:56.0160809Z "Install CMake 3.22.1 v.3.22.1" finished.
2026-09-26T19:30:02.1177940Z 
2026-09-26T19:30:02.1225265Z > Task :app:buildCMakeDebug[arm64-v8a]
2026-09-26T19:30:02.3184011Z > Task :app:configureCMakeDebug[x86_64]
2026-09-26T19:30:06.7160102Z > Task :app:buildCMakeDebug[x86_64]
2026-09-26T19:30:06.7163111Z > Task :app:mergeDebugJniLibFolders
2026-09-26T19:30:07.3190733Z > Task :app:mergeDebugNativeLibs
2026-09-26T19:30:08.1161402Z > Task :app:stripDebugDebugSymbols
2026-09-26T19:30:09.3160733Z > Task :app:validateSigningDebug
2026-09-26T19:30:09.3187272Z > Task :app:writeDebugAppMetadata
2026-09-26T19:30:09.3210537Z > Task :app:writeDebugSigningConfigVersions
2026-09-26T19:30:10.7161803Z > Task :app:kspDebugKotlin
2026-09-26T19:30:40.7182997Z > Task :app:compileDebugKotlin
2026-09-26T19:30:42.2179075Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt:516:37 The corresponding parameter in the supertype 'WebSocketListener' is named 'webSocket'. This may cause problems when calling this function with named arguments.
2026-09-26T19:30:42.2263184Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt:556:40 The corresponding parameter in the supertype 'WebSocketListener' is named 'webSocket'. This may cause problems when calling this function with named arguments.
2026-09-26T19:30:42.2304292Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt:573:40 The corresponding parameter in the supertype 'WebSocketListener' is named 'webSocket'. This may cause problems when calling this function with named arguments.
2026-09-26T19:30:42.2323446Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt:580:40 The corresponding parameter in the supertype 'WebSocketListener' is named 'webSocket'. This may cause problems when calling this function with named arguments.
2026-09-26T19:30:42.2382230Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt:585:39 The corresponding parameter in the supertype 'WebSocketListener' is named 'webSocket'. This may cause problems when calling this function with named arguments.
2026-09-26T19:30:42.2405081Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt:601:40 The corresponding parameter in the supertype 'WebSocketListener' is named 'webSocket'. This may cause problems when calling this function with named arguments.
2026-09-26T19:30:42.2413432Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/audio/AudioDeviceRouter.kt:40:83 'static enum entry BLUETOOTH_COMMUNICATION: AudioRoutePath' is deprecated. Используйте BLUETOOTH_SCO или BLUETOOTH_BLE_HEADSET для точной настройки.
2026-09-26T19:30:42.2417331Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/audio/AudioDeviceRouter.kt:150:5 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
2026-09-26T19:30:42.2420332Z - To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
2026-09-26T19:30:42.2422042Z - To keep applying to the value parameter only, use the '@param:' annotation target.
2026-09-26T19:30:42.2422751Z 
2026-09-26T19:30:42.2423366Z See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
2026-09-26T19:30:42.2425591Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/audio/AudioDeviceRouter.kt:437:76 'var isBluetoothScoOn: Boolean' is deprecated. Deprecated in Java.
2026-09-26T19:30:42.2429858Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/audio/AudioDeviceRouter.kt:840:28 'static enum entry BLUETOOTH_COMMUNICATION: AudioRoutePath' is deprecated. Используйте BLUETOOTH_SCO или BLUETOOTH_BLE_HEADSET для точной настройки.
2026-09-26T19:30:42.2433689Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/audio/NativeAudioEngine.kt:78:5 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
2026-09-26T19:30:42.2436761Z - To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
2026-09-26T19:30:42.2438428Z - To keep applying to the value parameter only, use the '@param:' annotation target.
2026-09-26T19:30:42.2439107Z 
2026-09-26T19:30:42.2448226Z See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
2026-09-26T19:30:42.2451257Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/haptics/HapticBargeInManager.kt:16:5 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
2026-09-26T19:30:42.2453939Z - To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
2026-09-26T19:30:42.2455574Z - To keep applying to the value parameter only, use the '@param:' annotation target.
2026-09-26T19:30:42.2456217Z 
2026-09-26T19:30:42.2456767Z See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
2026-09-26T19:30:42.2459274Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/logging/AppLogManager.kt:47:5 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
2026-09-26T19:30:42.2462138Z - To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
2026-09-26T19:30:42.2463786Z - To keep applying to the value parameter only, use the '@param:' annotation target.
2026-09-26T19:30:42.2464441Z 
2026-09-26T19:30:42.2464976Z See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
2026-09-26T19:30:42.2467400Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/service/LiveSessionForegroundService.kt:122:45 'static field FLAG_HANDLES_MEDIA_BUTTONS: Int' is deprecated. Deprecated in Java.
2026-09-26T19:30:42.2470900Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/session/SessionManager.kt:94:5 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
2026-09-26T19:30:42.2473500Z - To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
2026-09-26T19:30:42.2475119Z - To keep applying to the value parameter only, use the '@param:' annotation target.
2026-09-26T19:30:42.2475737Z 
2026-09-26T19:30:42.2476456Z See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
2026-09-26T19:30:42.2478015Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/ui/screens/ClientScreen.kt:551:51 'val Icons.Filled.VolumeOff: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.VolumeOff.
2026-09-26T19:30:42.2480867Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/ui/screens/ClientScreen.kt:552:42 'val Icons.Filled.VolumeUp: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.VolumeUp.
2026-09-26T19:30:42.2484441Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/util/AttachmentProcessor.kt:23:5 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
2026-09-26T19:30:42.2486987Z - To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
2026-09-26T19:30:42.2488062Z - To keep applying to the value parameter only, use the '@param:' annotation target.
2026-09-26T19:30:42.2488429Z 
2026-09-26T19:30:42.2488734Z See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
2026-09-26T19:30:42.2490549Z w: file:///home/runner/work/Client/Client/app/src/main/java/com/client/app/vad/SileroVadDetector.kt:40:5 This annotation is currently applied to the value parameter only, but in the future it will also be applied to field.
2026-09-26T19:30:42.2493068Z - To opt in to applying to both value parameter and field, add '-Xannotation-default-target=param-property' to your compiler arguments.
2026-09-26T19:30:42.2494326Z - To keep applying to the value parameter only, use the '@param:' annotation target.
2026-09-26T19:30:42.2494987Z 
2026-09-26T19:30:42.2495513Z See https://youtrack.jetbrains.com/issue/KT-73255 for more details.
2026-09-26T19:30:46.0162114Z 
2026-09-26T19:30:46.0177269Z > Task :app:compileDebugJavaWithJavac
2026-09-26T19:30:46.3163052Z > Task :app:hiltAggregateDepsDebug FROM-CACHE
2026-09-26T19:30:48.4170840Z > Task :app:hiltJavaCompileDebug
2026-09-26T19:30:48.6158540Z > Task :app:processDebugJavaRes
2026-09-26T19:30:48.7158838Z > Task :app:bundleDebugClassesToCompileJar
2026-09-26T19:30:49.0181670Z > Task :app:transformDebugClassesWithAsm
2026-09-26T19:30:49.5158657Z > Task :app:bundleDebugClassesToRuntimeJar
2026-09-26T19:30:49.9158253Z > Task :app:kspDebugUnitTestKotlin
2026-09-26T19:30:50.1158259Z > Task :app:generateDebugAndroidTestLintModel
2026-09-26T19:30:50.7167177Z > Task :app:generateDebugLintReportModel
2026-09-26T19:30:50.7194525Z > Task :app:generateDebugUnitTestLintModel
2026-09-26T19:30:51.6157586Z 
2026-09-26T19:30:51.6162578Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:4:27 Unresolved reference 'test'.
2026-09-26T19:30:51.6165633Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:52:58 Unresolved reference 'runTest'.
2026-09-26T19:30:51.6170118Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:57:24 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
2026-09-26T19:30:51.6172038Z > Task :app:compileDebugUnitTestKotlin FAILED
2026-09-26T19:30:51.6173547Z fun CoroutineScope.launch(context: CoroutineContext = ..., start: CoroutineStart = ..., block: suspend CoroutineScope.() -> Unit): Job
2026-09-26T19:30:51.6176378Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:69:9 Suspend function 'suspend fun delay(timeMillis: Long): Unit' can only be called from a coroutine or another suspend function.
2026-09-26T19:30:51.6181097Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:74:9 Suspend function 'suspend fun <T> withTimeout(timeMillis: Long, block: suspend CoroutineScope.() -> T): T' can only be called from a coroutine or another suspend function.
2026-09-26T19:30:51.6184238Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:87:44 Unresolved reference 'runTest'.
2026-09-26T19:30:51.6187419Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:94:13 Suspend function 'suspend fun delay(timeMillis: Long): Unit' can only be called from a coroutine or another suspend function.
2026-09-26T19:30:51.6193263Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:98:9 Suspend function 'suspend fun delay(timeMillis: Long): Unit' can only be called from a coroutine or another suspend function.
2026-09-26T19:30:51.6195902Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:110:50 Unresolved reference 'runTest'.
2026-09-26T19:30:51.6198941Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:115:20 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
2026-09-26T19:30:51.6201747Z fun CoroutineScope.launch(context: CoroutineContext = ..., start: CoroutineStart = ..., block: suspend CoroutineScope.() -> Unit): Job
2026-09-26T19:30:51.6204557Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:118:21 The 'delay' suspension point is inside a critical section.
2026-09-26T19:30:51.6207509Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:126:20 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
2026-09-26T19:30:51.6210209Z fun CoroutineScope.launch(context: CoroutineContext = ..., start: CoroutineStart = ..., block: suspend CoroutineScope.() -> Unit): Job
2026-09-26T19:30:51.6212612Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:130:21 The 'delay' suspension point is inside a critical section.
2026-09-26T19:30:51.6216289Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:138:9 Suspend function 'suspend fun <T> withTimeout(timeMillis: Long, block: suspend CoroutineScope.() -> T): T' can only be called from a coroutine or another suspend function.
2026-09-26T19:30:51.6219678Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:150:52 Unresolved reference 'runTest'.
2026-09-26T19:30:51.6222713Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:155:26 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
2026-09-26T19:30:51.6225188Z fun CoroutineScope.launch(context: CoroutineContext = ..., start: CoroutineStart = ..., block: suspend CoroutineScope.() -> Unit): Job
2026-09-26T19:30:51.6228139Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:168:9 Suspend function 'suspend fun delay(timeMillis: Long): Unit' can only be called from a coroutine or another suspend function.
2026-09-26T19:30:51.6232015Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:171:20 Suspend function 'suspend fun join(): Unit' can only be called from a coroutine or another suspend function.
2026-09-26T19:30:51.6234892Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:182:44 Unresolved reference 'runTest'.
2026-09-26T19:30:51.6237843Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:185:24 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
2026-09-26T19:30:51.6240916Z fun CoroutineScope.launch(context: CoroutineContext = ..., start: CoroutineStart = ..., block: suspend CoroutineScope.() -> Unit): Job
2026-09-26T19:30:51.6243673Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:194:18 Suspend function 'suspend fun join(): Unit' can only be called from a coroutine or another suspend function.
2026-09-26T19:30:51.6246530Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:220:47 Unresolved reference 'runTest'.
2026-09-26T19:30:51.6249796Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:225:27 Unresolved reference. None of the following candidates is applicable because of a receiver type mismatch:
2026-09-26T19:30:51.6252556Z fun CoroutineScope.launch(context: CoroutineContext = ..., start: CoroutineStart = ..., block: suspend CoroutineScope.() -> Unit): Job
2026-09-26T19:30:51.6255450Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:233:9 Suspend function 'suspend fun delay(timeMillis: Long): Unit' can only be called from a coroutine or another suspend function.
2026-09-26T19:30:51.6258941Z e: file:///home/runner/work/Client/Client/app/src/test/java/com/client/app/audio/AudioRoutingIntegrationTest.kt:236:21 Suspend function 'suspend fun join(): Unit' can only be called from a coroutine or another suspend function.
2026-09-26T19:31:20.7160272Z 
2026-09-26T19:31:20.7161405Z > Task :app:lintAnalyzeDebug
2026-09-26T19:31:40.9159649Z gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_5-1790450980048.json
2026-09-26T19:31:41.0185945Z 
2026-09-26T19:31:41.0202522Z FAILURE: Build failed with an exception.
2026-09-26T19:31:41.0299870Z 
2026-09-26T19:31:41.0312339Z * What went wrong:
2026-09-26T19:31:41.0313444Z Execution failed for task ':app:compileDebugUnitTestKotlin'.
2026-09-26T19:31:41.0316547Z > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
2026-09-26T19:31:41.0318108Z    > Compilation error. See log for more details
2026-09-26T19:31:41.0318678Z 
2026-09-26T19:31:41.0318891Z * Try:
2026-09-26T19:31:41.0319908Z > Run with --info or --debug option to get more log output.
2026-09-26T19:31:41.0320632Z > Run with --scan to get full insights.
2026-09-26T19:31:41.0321073Z > Get more help at https://help.gradle.org.
2026-09-26T19:31:41.0321323Z 
2026-09-26T19:31:41.0321453Z * Exception is:
2026-09-26T19:31:41.0322102Z org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':app:compileDebugUnitTestKotlin'.
2026-09-26T19:31:41.0323218Z 	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.lambda$executeIfValid$1(ExecuteActionsTaskExecuter.java:130)
2026-09-26T19:31:41.0324128Z 	at org.gradle.internal.Try$Failure.ifSuccessfulOrElse(Try.java:293)
2026-09-26T19:31:41.0325041Z 	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.executeIfValid(ExecuteActionsTaskExecuter.java:128)
2026-09-26T19:31:41.0326155Z 	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.execute(ExecuteActionsTaskExecuter.java:116)
2026-09-26T19:31:41.0328176Z 	at org.gradle.api.internal.tasks.execution.ProblemsTaskPathTrackingTaskExecuter.execute(ProblemsTaskPathTrackingTaskExecuter.java:41)
2026-09-26T19:31:41.0330431Z 	at org.gradle.api.internal.tasks.execution.FinalizePropertiesTaskExecuter.execute(FinalizePropertiesTaskExecuter.java:46)
2026-09-26T19:31:41.0332371Z 	at org.gradle.api.internal.tasks.execution.ResolveTaskExecutionModeExecuter.execute(ResolveTaskExecutionModeExecuter.java:51)
2026-09-26T19:31:41.0334230Z 	at org.gradle.api.internal.tasks.execution.SkipTaskWithNoActionsExecuter.execute(SkipTaskWithNoActionsExecuter.java:57)
2026-09-26T19:31:41.0335952Z 	at org.gradle.api.internal.tasks.execution.SkipOnlyIfTaskExecuter.execute(SkipOnlyIfTaskExecuter.java:74)
2026-09-26T19:31:41.0338085Z 	at org.gradle.api.internal.tasks.execution.CatchExceptionTaskExecuter.execute(CatchExceptionTaskExecuter.java:36)
2026-09-26T19:31:41.0340136Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.executeTask(EventFiringTaskExecuter.java:77)
2026-09-26T19:31:41.0341774Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:55)
2026-09-26T19:31:41.0343414Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:52)
2026-09-26T19:31:41.0345286Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-26T19:31:41.0347419Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-26T19:31:41.0350392Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-26T19:31:41.0352156Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0353835Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-26T19:31:41.0355747Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0357470Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-26T19:31:41.0359153Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter.execute(EventFiringTaskExecuter.java:52)
2026-09-26T19:31:41.0361001Z 	at org.gradle.execution.plan.LocalTaskNodeExecutor.execute(LocalTaskNodeExecutor.java:42)
2026-09-26T19:31:41.0362717Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:331)
2026-09-26T19:31:41.0364720Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:318)
2026-09-26T19:31:41.0366886Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.lambda$execute$0(DefaultTaskExecutionGraph.java:314)
2026-09-26T19:31:41.0368753Z 	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:85)
2026-09-26T19:31:41.0370981Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:314)
2026-09-26T19:31:41.0373138Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:303)
2026-09-26T19:31:41.0374933Z 	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.execute(DefaultPlanExecutor.java:459)
2026-09-26T19:31:41.0376383Z 	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.run(DefaultPlanExecutor.java:376)
2026-09-26T19:31:41.0377777Z 	at org.gradle.execution.plan.DefaultPlanExecutor.process(DefaultPlanExecutor.java:111)
2026-09-26T19:31:41.0379645Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph.executeWithServices(DefaultTaskExecutionGraph.java:138)
2026-09-26T19:31:41.0383614Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph.execute(DefaultTaskExecutionGraph.java:123)
2026-09-26T19:31:41.0385246Z 	at org.gradle.execution.SelectedTaskExecutionAction.execute(SelectedTaskExecutionAction.java:35)
2026-09-26T19:31:41.0386922Z 	at org.gradle.execution.DryRunBuildExecutionAction.execute(DryRunBuildExecutionAction.java:51)
2026-09-26T19:31:41.0388718Z 	at org.gradle.execution.BuildOperationFiringBuildWorkerExecutor$ExecuteTasks.call(BuildOperationFiringBuildWorkerExecutor.java:54)
2026-09-26T19:31:41.0391127Z 	at org.gradle.execution.BuildOperationFiringBuildWorkerExecutor$ExecuteTasks.call(BuildOperationFiringBuildWorkerExecutor.java:43)
2026-09-26T19:31:41.0393532Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-26T19:31:41.0395692Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-26T19:31:41.0397595Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-26T19:31:41.0399281Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0401139Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-26T19:31:41.0402779Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0404386Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-26T19:31:41.0406146Z 	at org.gradle.execution.BuildOperationFiringBuildWorkerExecutor.execute(BuildOperationFiringBuildWorkerExecutor.java:40)
2026-09-26T19:31:41.0408034Z 	at org.gradle.internal.build.DefaultBuildLifecycleController.lambda$executeTasks$10(DefaultBuildLifecycleController.java:313)
2026-09-26T19:31:41.0410595Z 	at org.gradle.internal.model.StateTransitionController.doTransition(StateTransitionController.java:266)
2026-09-26T19:31:41.0412490Z 	at org.gradle.internal.model.StateTransitionController.lambda$tryTransition$8(StateTransitionController.java:177)
2026-09-26T19:31:41.0413951Z 	at org.gradle.internal.work.DefaultSynchronizer.withLock(DefaultSynchronizer.java:46)
2026-09-26T19:31:41.0415404Z 	at org.gradle.internal.model.StateTransitionController.tryTransition(StateTransitionController.java:177)
2026-09-26T19:31:41.0417093Z 	at org.gradle.internal.build.DefaultBuildLifecycleController.executeTasks(DefaultBuildLifecycleController.java:304)
2026-09-26T19:31:41.0419011Z 	at org.gradle.internal.build.DefaultBuildWorkGraphController$DefaultBuildWorkGraph.runWork(DefaultBuildWorkGraphController.java:220)
2026-09-26T19:31:41.0421299Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:263)
2026-09-26T19:31:41.0422885Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:127)
2026-09-26T19:31:41.0424435Z 	at org.gradle.composite.internal.DefaultBuildController.doRun(DefaultBuildController.java:181)
2026-09-26T19:31:41.0425895Z 	at org.gradle.composite.internal.DefaultBuildController.access$000(DefaultBuildController.java:50)
2026-09-26T19:31:41.0427515Z 	at org.gradle.composite.internal.DefaultBuildController$BuildOpRunnable.lambda$run$0(DefaultBuildController.java:198)
2026-09-26T19:31:41.0429118Z 	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:85)
2026-09-26T19:31:41.0430904Z 	at org.gradle.composite.internal.DefaultBuildController$BuildOpRunnable.run(DefaultBuildController.java:198)
2026-09-26T19:31:41.0432495Z 	at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
2026-09-26T19:31:41.0433977Z 	at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:48)
2026-09-26T19:31:41.0436445Z Caused by: org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException: A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
2026-09-26T19:31:41.0438886Z 	at org.gradle.workers.internal.DefaultWorkerExecutor$WorkItemExecution.waitForCompletion(DefaultWorkerExecutor.java:287)
2026-09-26T19:31:41.0441321Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.lambda$waitForItemsAndGatherFailures$2(DefaultAsyncWorkTracker.java:130)
2026-09-26T19:31:41.0442688Z 	at org.gradle.internal.Factories$1.create(Factories.java:31)
2026-09-26T19:31:41.0444008Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withoutLocks(DefaultWorkerLeaseService.java:335)
2026-09-26T19:31:41.0445862Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withoutLocks(DefaultWorkerLeaseService.java:318)
2026-09-26T19:31:41.0447459Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withoutLock(DefaultWorkerLeaseService.java:323)
2026-09-26T19:31:41.0449161Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForItemsAndGatherFailures(DefaultAsyncWorkTracker.java:126)
2026-09-26T19:31:41.0451176Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForItemsAndGatherFailures(DefaultAsyncWorkTracker.java:92)
2026-09-26T19:31:41.0452817Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForAll(DefaultAsyncWorkTracker.java:78)
2026-09-26T19:31:41.0454339Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForCompletion(DefaultAsyncWorkTracker.java:66)
2026-09-26T19:31:41.0455756Z 	at org.gradle.api.internal.tasks.execution.TaskExecution$3.run(TaskExecution.java:252)
2026-09-26T19:31:41.0457246Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$1.execute(DefaultBuildOperationRunner.java:30)
2026-09-26T19:31:41.0458893Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$1.execute(DefaultBuildOperationRunner.java:27)
2026-09-26T19:31:41.0460717Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-26T19:31:41.0462557Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0464205Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-26T19:31:41.0465872Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0467545Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.run(DefaultBuildOperationRunner.java:48)
2026-09-26T19:31:41.0469082Z 	at org.gradle.api.internal.tasks.execution.TaskExecution.executeAction(TaskExecution.java:229)
2026-09-26T19:31:41.0471092Z 	at org.gradle.api.internal.tasks.execution.TaskExecution.executeActions(TaskExecution.java:212)
2026-09-26T19:31:41.0472783Z 	at org.gradle.api.internal.tasks.execution.TaskExecution.executeWithPreviousOutputFiles(TaskExecution.java:195)
2026-09-26T19:31:41.0474326Z 	at org.gradle.api.internal.tasks.execution.TaskExecution.execute(TaskExecution.java:162)
2026-09-26T19:31:41.0475650Z 	at org.gradle.internal.execution.steps.ExecuteStep.executeInternal(ExecuteStep.java:105)
2026-09-26T19:31:41.0476922Z 	at org.gradle.internal.execution.steps.ExecuteStep.access$000(ExecuteStep.java:44)
2026-09-26T19:31:41.0478104Z 	at org.gradle.internal.execution.steps.ExecuteStep$1.call(ExecuteStep.java:59)
2026-09-26T19:31:41.0479238Z 	at org.gradle.internal.execution.steps.ExecuteStep$1.call(ExecuteStep.java:56)
2026-09-26T19:31:41.0481098Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-26T19:31:41.0483198Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-26T19:31:41.0485100Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-26T19:31:41.0486749Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0488389Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-26T19:31:41.0490313Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0491929Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-26T19:31:41.0493308Z 	at org.gradle.internal.execution.steps.ExecuteStep.execute(ExecuteStep.java:56)
2026-09-26T19:31:41.0494476Z 	at org.gradle.internal.execution.steps.ExecuteStep.execute(ExecuteStep.java:44)
2026-09-26T19:31:41.0496111Z 	at org.gradle.internal.execution.steps.CancelExecutionStep.execute(CancelExecutionStep.java:42)
2026-09-26T19:31:41.0497573Z 	at org.gradle.internal.execution.steps.TimeoutStep.executeWithoutTimeout(TimeoutStep.java:75)
2026-09-26T19:31:41.0498870Z 	at org.gradle.internal.execution.steps.TimeoutStep.execute(TimeoutStep.java:55)
2026-09-26T19:31:41.0506631Z 	at org.gradle.internal.execution.steps.PreCreateOutputParentsStep.execute(PreCreateOutputParentsStep.java:50)
2026-09-26T19:31:41.0508396Z 	at org.gradle.internal.execution.steps.PreCreateOutputParentsStep.execute(PreCreateOutputParentsStep.java:28)
2026-09-26T19:31:41.0510311Z 	at org.gradle.internal.execution.steps.RemovePreviousOutputsStep.execute(RemovePreviousOutputsStep.java:67)
2026-09-26T19:31:41.0512012Z 	at org.gradle.internal.execution.steps.RemovePreviousOutputsStep.execute(RemovePreviousOutputsStep.java:37)
2026-09-26T19:31:41.0513721Z 	at org.gradle.internal.execution.steps.BroadcastChangingOutputsStep.execute(BroadcastChangingOutputsStep.java:61)
2026-09-26T19:31:41.0515539Z 	at org.gradle.internal.execution.steps.BroadcastChangingOutputsStep.execute(BroadcastChangingOutputsStep.java:26)
2026-09-26T19:31:41.0517364Z 	at org.gradle.internal.execution.steps.CaptureOutputsAfterExecutionStep.execute(CaptureOutputsAfterExecutionStep.java:69)
2026-09-26T19:31:41.0519760Z 	at org.gradle.internal.execution.steps.CaptureOutputsAfterExecutionStep.execute(CaptureOutputsAfterExecutionStep.java:46)
2026-09-26T19:31:41.0521671Z 	at org.gradle.internal.execution.steps.ResolveInputChangesStep.execute(ResolveInputChangesStep.java:40)
2026-09-26T19:31:41.0522692Z 	at org.gradle.internal.execution.steps.ResolveInputChangesStep.execute(ResolveInputChangesStep.java:29)
2026-09-26T19:31:41.0523637Z 	at org.gradle.internal.execution.steps.BuildCacheStep.executeWithoutCache(BuildCacheStep.java:189)
2026-09-26T19:31:41.0524581Z 	at org.gradle.internal.execution.steps.BuildCacheStep.executeAndStoreInCache(BuildCacheStep.java:145)
2026-09-26T19:31:41.0525525Z 	at org.gradle.internal.execution.steps.BuildCacheStep.lambda$executeWithCache$4(BuildCacheStep.java:101)
2026-09-26T19:31:41.0526449Z 	at org.gradle.internal.execution.steps.BuildCacheStep.lambda$executeWithCache$5(BuildCacheStep.java:101)
2026-09-26T19:31:41.0527131Z 	at org.gradle.internal.Try$Success.map(Try.java:175)
2026-09-26T19:31:41.0527821Z 	at org.gradle.internal.execution.steps.BuildCacheStep.executeWithCache(BuildCacheStep.java:85)
2026-09-26T19:31:41.0528680Z 	at org.gradle.internal.execution.steps.BuildCacheStep.lambda$execute$0(BuildCacheStep.java:74)
2026-09-26T19:31:41.0537794Z 	at org.gradle.internal.Either$Left.fold(Either.java:115)
2026-09-26T19:31:41.0538641Z 	at org.gradle.internal.execution.caching.CachingState.fold(CachingState.java:62)
2026-09-26T19:31:41.0539650Z 	at org.gradle.internal.execution.steps.BuildCacheStep.execute(BuildCacheStep.java:73)
2026-09-26T19:31:41.0540953Z 	at org.gradle.internal.execution.steps.BuildCacheStep.execute(BuildCacheStep.java:48)
2026-09-26T19:31:41.0542480Z 	at org.gradle.internal.execution.steps.StoreExecutionStateStep.execute(StoreExecutionStateStep.java:46)
2026-09-26T19:31:41.0543793Z 	at org.gradle.internal.execution.steps.StoreExecutionStateStep.execute(StoreExecutionStateStep.java:35)
2026-09-26T19:31:41.0545198Z 	at org.gradle.internal.execution.steps.SkipUpToDateStep.executeBecause(SkipUpToDateStep.java:75)
2026-09-26T19:31:41.0546955Z 	at org.gradle.internal.execution.steps.SkipUpToDateStep.lambda$execute$2(SkipUpToDateStep.java:53)
2026-09-26T19:31:41.0548635Z 	at org.gradle.internal.execution.steps.SkipUpToDateStep.execute(SkipUpToDateStep.java:53)
2026-09-26T19:31:41.0550515Z 	at org.gradle.internal.execution.steps.SkipUpToDateStep.execute(SkipUpToDateStep.java:35)
2026-09-26T19:31:41.0552624Z 	at org.gradle.internal.execution.steps.legacy.MarkSnapshottingInputsFinishedStep.execute(MarkSnapshottingInputsFinishedStep.java:37)
2026-09-26T19:31:41.0555399Z 	at org.gradle.internal.execution.steps.legacy.MarkSnapshottingInputsFinishedStep.execute(MarkSnapshottingInputsFinishedStep.java:27)
2026-09-26T19:31:41.0557964Z 	at org.gradle.internal.execution.steps.ResolveIncrementalCachingStateStep.executeDelegate(ResolveIncrementalCachingStateStep.java:49)
2026-09-26T19:31:41.0560814Z 	at org.gradle.internal.execution.steps.ResolveIncrementalCachingStateStep.executeDelegate(ResolveIncrementalCachingStateStep.java:27)
2026-09-26T19:31:41.0563220Z 	at org.gradle.internal.execution.steps.AbstractResolveCachingStateStep.execute(AbstractResolveCachingStateStep.java:71)
2026-09-26T19:31:41.0565464Z 	at org.gradle.internal.execution.steps.AbstractResolveCachingStateStep.execute(AbstractResolveCachingStateStep.java:39)
2026-09-26T19:31:41.0567406Z 	at org.gradle.internal.execution.steps.ResolveChangesStep.execute(ResolveChangesStep.java:65)
2026-09-26T19:31:41.0569099Z 	at org.gradle.internal.execution.steps.ResolveChangesStep.execute(ResolveChangesStep.java:36)
2026-09-26T19:31:41.0570690Z 	at org.gradle.internal.execution.steps.ValidateStep.execute(ValidateStep.java:107)
2026-09-26T19:31:41.0571519Z 	at org.gradle.internal.execution.steps.ValidateStep.execute(ValidateStep.java:56)
2026-09-26T19:31:41.0573287Z 	at org.gradle.internal.execution.steps.AbstractCaptureStateBeforeExecutionStep.execute(AbstractCaptureStateBeforeExecutionStep.java:64)
2026-09-26T19:31:41.0575723Z 	at org.gradle.internal.execution.steps.AbstractCaptureStateBeforeExecutionStep.execute(AbstractCaptureStateBeforeExecutionStep.java:43)
2026-09-26T19:31:41.0577793Z 	at org.gradle.internal.execution.steps.AbstractSkipEmptyWorkStep.executeWithNonEmptySources(AbstractSkipEmptyWorkStep.java:125)
2026-09-26T19:31:41.0579788Z 	at org.gradle.internal.execution.steps.AbstractSkipEmptyWorkStep.execute(AbstractSkipEmptyWorkStep.java:61)
2026-09-26T19:31:41.0581444Z 	at org.gradle.internal.execution.steps.AbstractSkipEmptyWorkStep.execute(AbstractSkipEmptyWorkStep.java:36)
2026-09-26T19:31:41.0583303Z 	at org.gradle.internal.execution.steps.legacy.MarkSnapshottingInputsStartedStep.execute(MarkSnapshottingInputsStartedStep.java:38)
2026-09-26T19:31:41.0585351Z 	at org.gradle.internal.execution.steps.LoadPreviousExecutionStateStep.execute(LoadPreviousExecutionStateStep.java:36)
2026-09-26T19:31:41.0587293Z 	at org.gradle.internal.execution.steps.LoadPreviousExecutionStateStep.execute(LoadPreviousExecutionStateStep.java:23)
2026-09-26T19:31:41.0588970Z 	at org.gradle.internal.execution.steps.HandleStaleOutputsStep.execute(HandleStaleOutputsStep.java:75)
2026-09-26T19:31:41.0590683Z 	at org.gradle.internal.execution.steps.HandleStaleOutputsStep.execute(HandleStaleOutputsStep.java:41)
2026-09-26T19:31:41.0592393Z 	at org.gradle.internal.execution.steps.AssignMutableWorkspaceStep.lambda$execute$0(AssignMutableWorkspaceStep.java:35)
2026-09-26T19:31:41.0594235Z 	at org.gradle.api.internal.tasks.execution.TaskExecution$4.withWorkspace(TaskExecution.java:289)
2026-09-26T19:31:41.0596039Z 	at org.gradle.internal.execution.steps.AssignMutableWorkspaceStep.execute(AssignMutableWorkspaceStep.java:31)
2026-09-26T19:31:41.0597965Z 	at org.gradle.internal.execution.steps.AssignMutableWorkspaceStep.execute(AssignMutableWorkspaceStep.java:22)
2026-09-26T19:31:41.0599945Z 	at org.gradle.internal.execution.steps.ChoosePipelineStep.execute(ChoosePipelineStep.java:40)
2026-09-26T19:31:41.0601565Z 	at org.gradle.internal.execution.steps.ChoosePipelineStep.execute(ChoosePipelineStep.java:23)
2026-09-26T19:31:41.0603645Z 	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.lambda$execute$2(ExecuteWorkBuildOperationFiringStep.java:67)
2026-09-26T19:31:41.0605929Z 	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.execute(ExecuteWorkBuildOperationFiringStep.java:67)
2026-09-26T19:31:41.0608171Z 	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.execute(ExecuteWorkBuildOperationFiringStep.java:39)
2026-09-26T19:31:41.0610278Z 	at org.gradle.internal.execution.steps.IdentityCacheStep.execute(IdentityCacheStep.java:46)
2026-09-26T19:31:41.0612066Z 	at org.gradle.internal.execution.steps.IdentityCacheStep.execute(IdentityCacheStep.java:34)
2026-09-26T19:31:41.0613523Z 	at org.gradle.internal.execution.steps.IdentifyStep.execute(IdentifyStep.java:48)
2026-09-26T19:31:41.0614931Z 	at org.gradle.internal.execution.steps.IdentifyStep.execute(IdentifyStep.java:35)
2026-09-26T19:31:41.0616540Z 	at org.gradle.internal.execution.impl.DefaultExecutionEngine$1.execute(DefaultExecutionEngine.java:64)
2026-09-26T19:31:41.0618507Z 	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.executeIfValid(ExecuteActionsTaskExecuter.java:127)
2026-09-26T19:31:41.0620760Z 	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.execute(ExecuteActionsTaskExecuter.java:116)
2026-09-26T19:31:41.0622703Z 	at org.gradle.api.internal.tasks.execution.ProblemsTaskPathTrackingTaskExecuter.execute(ProblemsTaskPathTrackingTaskExecuter.java:41)
2026-09-26T19:31:41.0624692Z 	at org.gradle.api.internal.tasks.execution.FinalizePropertiesTaskExecuter.execute(FinalizePropertiesTaskExecuter.java:46)
2026-09-26T19:31:41.0626597Z 	at org.gradle.api.internal.tasks.execution.ResolveTaskExecutionModeExecuter.execute(ResolveTaskExecutionModeExecuter.java:51)
2026-09-26T19:31:41.0628525Z 	at org.gradle.api.internal.tasks.execution.SkipTaskWithNoActionsExecuter.execute(SkipTaskWithNoActionsExecuter.java:57)
2026-09-26T19:31:41.0630806Z 	at org.gradle.api.internal.tasks.execution.SkipOnlyIfTaskExecuter.execute(SkipOnlyIfTaskExecuter.java:74)
2026-09-26T19:31:41.0632510Z 	at org.gradle.api.internal.tasks.execution.CatchExceptionTaskExecuter.execute(CatchExceptionTaskExecuter.java:36)
2026-09-26T19:31:41.0634264Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.executeTask(EventFiringTaskExecuter.java:77)
2026-09-26T19:31:41.0635871Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:55)
2026-09-26T19:31:41.0637457Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:52)
2026-09-26T19:31:41.0640896Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-26T19:31:41.0645819Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-26T19:31:41.0647695Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-26T19:31:41.0649581Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0651255Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-26T19:31:41.0652881Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0654484Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-26T19:31:41.0656109Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter.execute(EventFiringTaskExecuter.java:52)
2026-09-26T19:31:41.0657569Z 	at org.gradle.execution.plan.LocalTaskNodeExecutor.execute(LocalTaskNodeExecutor.java:42)
2026-09-26T19:31:41.0659262Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:331)
2026-09-26T19:31:41.0661550Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:318)
2026-09-26T19:31:41.0663690Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.lambda$execute$0(DefaultTaskExecutionGraph.java:314)
2026-09-26T19:31:41.0665534Z 	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:85)
2026-09-26T19:31:41.0667643Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:314)
2026-09-26T19:31:41.0670086Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:303)
2026-09-26T19:31:41.0672087Z 	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.execute(DefaultPlanExecutor.java:459)
2026-09-26T19:31:41.0673739Z 	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.run(DefaultPlanExecutor.java:376)
2026-09-26T19:31:41.0675273Z 	at org.gradle.execution.plan.DefaultPlanExecutor.process(DefaultPlanExecutor.java:111)
2026-09-26T19:31:41.0677047Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph.executeWithServices(DefaultTaskExecutionGraph.java:138)
2026-09-26T19:31:41.0678880Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph.execute(DefaultTaskExecutionGraph.java:123)
2026-09-26T19:31:41.0680864Z 	at org.gradle.execution.SelectedTaskExecutionAction.execute(SelectedTaskExecutionAction.java:35)
2026-09-26T19:31:41.0682403Z 	at org.gradle.execution.DryRunBuildExecutionAction.execute(DryRunBuildExecutionAction.java:51)
2026-09-26T19:31:41.0684329Z 	at org.gradle.execution.BuildOperationFiringBuildWorkerExecutor$ExecuteTasks.call(BuildOperationFiringBuildWorkerExecutor.java:54)
2026-09-26T19:31:41.0686635Z 	at org.gradle.execution.BuildOperationFiringBuildWorkerExecutor$ExecuteTasks.call(BuildOperationFiringBuildWorkerExecutor.java:43)
2026-09-26T19:31:41.0689017Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-26T19:31:41.0698730Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-26T19:31:41.0700876Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-26T19:31:41.0702515Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0704183Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-26T19:31:41.0705830Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0707456Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-26T19:31:41.0709241Z 	at org.gradle.execution.BuildOperationFiringBuildWorkerExecutor.execute(BuildOperationFiringBuildWorkerExecutor.java:40)
2026-09-26T19:31:41.0711442Z 	at org.gradle.internal.build.DefaultBuildLifecycleController.lambda$executeTasks$10(DefaultBuildLifecycleController.java:313)
2026-09-26T19:31:41.0713157Z 	at org.gradle.internal.model.StateTransitionController.doTransition(StateTransitionController.java:266)
2026-09-26T19:31:41.0714809Z 	at org.gradle.internal.model.StateTransitionController.lambda$tryTransition$8(StateTransitionController.java:177)
2026-09-26T19:31:41.0716256Z 	at org.gradle.internal.work.DefaultSynchronizer.withLock(DefaultSynchronizer.java:46)
2026-09-26T19:31:41.0717701Z 	at org.gradle.internal.model.StateTransitionController.tryTransition(StateTransitionController.java:177)
2026-09-26T19:31:41.0719603Z 	at org.gradle.internal.build.DefaultBuildLifecycleController.executeTasks(DefaultBuildLifecycleController.java:304)
2026-09-26T19:31:41.0721759Z 	at org.gradle.internal.build.DefaultBuildWorkGraphController$DefaultBuildWorkGraph.runWork(DefaultBuildWorkGraphController.java:220)
2026-09-26T19:31:41.0723785Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:263)
2026-09-26T19:31:41.0725609Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:127)
2026-09-26T19:31:41.0727349Z 	at org.gradle.composite.internal.DefaultBuildController.doRun(DefaultBuildController.java:181)
2026-09-26T19:31:41.0728988Z 	at org.gradle.composite.internal.DefaultBuildController.access$000(DefaultBuildController.java:50)
2026-09-26T19:31:41.0741639Z 	at org.gradle.composite.internal.DefaultBuildController$BuildOpRunnable.lambda$run$0(DefaultBuildController.java:198)
2026-09-26T19:31:41.0743301Z 	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:85)
2026-09-26T19:31:41.0744332Z 	at org.gradle.composite.internal.DefaultBuildController$BuildOpRunnable.run(DefaultBuildController.java:198)
2026-09-26T19:31:41.0745315Z 	at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
2026-09-26T19:31:41.0746229Z 	at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:48)
2026-09-26T19:31:41.0747188Z Caused by: org.jetbrains.kotlin.gradle.tasks.CompilationErrorException: Compilation error. See log for more details
2026-09-26T19:31:41.0748193Z 	at org.jetbrains.kotlin.gradle.tasks.TasksUtilsKt.throwExceptionIfCompilationFailed(tasksUtils.kt:21)
2026-09-26T19:31:41.0749154Z 	at org.jetbrains.kotlin.compilerRunner.GradleKotlinCompilerWork.run(GradleKotlinCompilerWork.kt:115)
2026-09-26T19:31:41.0762007Z 	at org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction.execute(GradleCompilerRunnerWithWorkers.kt:74)
2026-09-26T19:31:41.0764180Z 	at org.gradle.workers.internal.DefaultWorkerServer.execute(DefaultWorkerServer.java:63)
2026-09-26T19:31:41.0765700Z 	at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:66)
2026-09-26T19:31:41.0767342Z 	at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:62)
2026-09-26T19:31:41.0768964Z 	at org.gradle.internal.classloader.ClassLoaderUtils.executeInClassloader(ClassLoaderUtils.java:100)
2026-09-26T19:31:41.0770871Z 	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.lambda$execute$0(NoIsolationWorkerFactory.java:62)
2026-09-26T19:31:41.0772307Z 	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:44)
2026-09-26T19:31:41.0773511Z 	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:41)
2026-09-26T19:31:41.0775312Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-26T19:31:41.0777582Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-26T19:31:41.0779812Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-26T19:31:41.0781607Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0783412Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-26T19:31:41.0785260Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-26T19:31:41.0787036Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-26T19:31:41.0788766Z 	at org.gradle.workers.internal.AbstractWorker.executeWrappedInBuildOperation(AbstractWorker.java:41)
2026-09-26T19:31:41.0790659Z 	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.execute(NoIsolationWorkerFactory.java:59)
2026-09-26T19:31:41.0792326Z 	at org.gradle.workers.internal.DefaultWorkerExecutor.lambda$submitWork$0(DefaultWorkerExecutor.java:174)
2026-09-26T19:31:41.0794301Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runExecution(DefaultConditionalExecutionQueue.java:194)
2026-09-26T19:31:41.0796485Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.access$700(DefaultConditionalExecutionQueue.java:127)
2026-09-26T19:31:41.0798565Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner$1.run(DefaultConditionalExecutionQueue.java:169)
2026-09-26T19:31:41.0800419Z 	at org.gradle.internal.Factories$1.create(Factories.java:31)
2026-09-26T19:31:41.0801813Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:263)
2026-09-26T19:31:41.0803542Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:127)
2026-09-26T19:31:41.0805341Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:132)
2026-09-26T19:31:41.0807331Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runBatch(DefaultConditionalExecutionQueue.java:164)
2026-09-26T19:31:41.0809626Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.run(DefaultConditionalExecutionQueue.java:133)
2026-09-26T19:31:41.0810817Z 	... 2 more
2026-09-26T19:31:41.0811027Z 
2026-09-26T19:31:41.0811038Z 
2026-09-26T19:31:41.0811275Z BUILD FAILED in 2m 4s
2026-09-26T19:31:41.0811880Z 52 actionable tasks: 31 executed, 21 from cache
2026-09-26T19:31:41.4564760Z ##[error]Process completed with exit code 1.
2026-09-26T19:31:41.4723516Z Post job cleanup.
2026-09-26T19:31:41.7204359Z In post-action step
2026-09-26T19:31:41.7217290Z ##[group]Stopping Gradle daemons
2026-09-26T19:31:41.7218648Z Stopping Gradle daemons for /home/runner/work/_temp/.gradle-actions/gradle-installations/installs/gradle-8.14.3
2026-09-26T19:31:41.7229798Z Stopping Gradle daemons for /home/runner/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3
2026-09-26T19:31:41.7236706Z [command]/home/runner/work/_temp/.gradle-actions/gradle-installations/installs/gradle-8.14.3/bin/gradle --stop
2026-09-26T19:31:41.7283112Z [command]/home/runner/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle --stop
2026-09-26T19:31:43.0106921Z Stopping Daemon(s)
2026-09-26T19:31:43.0481191Z Stopping Daemon(s)
2026-09-26T19:31:43.0491355Z 1 Daemon stopped
2026-09-26T19:31:43.0545624Z 1 Daemon stopped
2026-09-26T19:31:43.0644345Z ##[endgroup]
2026-09-26T19:31:43.0646527Z Not performing cache-cleanup due to build failure
2026-09-26T19:31:43.0648031Z ##[group]Caching Gradle state
2026-09-26T19:31:45.1230990Z [command]/usr/bin/tar --posix -cf cache.tzst --exclude cache.tzst -P -C /home/runner/work/Client/Client --files-from manifest.txt --use-compress-program zstdmt
2026-09-26T19:31:48.2589813Z Sent 180413837 of 247522701 (72.9%), 171.9 MBs/sec
2026-09-26T19:31:48.2958874Z Sent 247522701 of 247522701 (100.0%), 227.4 MBs/sec
2026-09-26T19:31:48.4143979Z Saved cache entry with key gradle-transforms-v1-de0c156b217cbb795b7614c7a4c40cca from /home/runner/.gradle/caches/transforms-4/*/,/home/runner/.gradle/caches/*/transforms/*/ in 4097ms
2026-09-26T19:31:50.2845833Z [command]/usr/bin/tar --posix -cf cache.tzst --exclude cache.tzst -P -C /home/runner/work/Client/Client --files-from manifest.txt --use-compress-program zstdmt
2026-09-26T19:31:50.8585626Z Sent 30862929 of 30862929 (100.0%), 90.6 MBs/sec
2026-09-26T19:31:50.9490228Z Saved cache entry with key gradle-home-v1|Linux-X64|build-and-test[5df42585ae8f8da228c604b8b4e8707c]-0be23825eaed73155ace9db7030d6ba49600ec9e from /home/runner/.gradle/caches,/home/runner/.gradle/notifications,/home/runner/.gradle/.setup-gradle in 678ms
2026-09-26T19:31:50.9491816Z ##[endgroup]
2026-09-26T19:31:50.9499428Z Generating Job Summary
2026-09-26T19:31:50.9514348Z Completed post-action step
2026-09-26T19:31:50.9774468Z Post job cleanup.
2026-09-26T19:31:51.1087533Z (node:3538) [DEP0040] DeprecationWarning: The `punycode` module is deprecated. Please use a userland alternative instead.
2026-09-26T19:31:51.1088488Z (Use `node --trace-deprecation ...` to show where the warning was created)
2026-09-26T19:31:51.1252440Z Post job cleanup.
2026-09-26T19:31:51.2196386Z [command]/usr/bin/git version
2026-09-26T19:31:51.2238024Z git version 2.55.0
2026-09-26T19:31:51.2280831Z Temporarily overriding HOME='/home/runner/work/_temp/78141d10-9700-4c8c-8626-962a1b64332d' before making global git config changes
2026-09-26T19:31:51.2282370Z Adding repository directory to the temporary git global config as a safe directory
2026-09-26T19:31:51.2283621Z [command]/usr/bin/git config --global --add safe.directory /home/runner/work/Client/Client
2026-09-26T19:31:51.2325429Z [command]/usr/bin/git config --local --name-only --get-regexp core\.sshCommand
2026-09-26T19:31:51.2362746Z [command]/usr/bin/git submodule foreach --recursive sh -c "git config --local --name-only --get-regexp 'core\.sshCommand' && git config --local --unset-all 'core.sshCommand' || :"
2026-09-26T19:31:51.2620179Z [command]/usr/bin/git config --local --name-only --get-regexp http\.https\:\/\/github\.com\/\.extraheader
2026-09-26T19:31:51.2641115Z http.https://github.com/.extraheader
2026-09-26T19:31:51.2680579Z [command]/usr/bin/git config --local --unset-all http.https://github.com/.extraheader
2026-09-26T19:31:51.2699080Z [command]/usr/bin/git submodule foreach --recursive sh -c "git config --local --name-only --get-regexp 'http\.https\:\/\/github\.com\/\.extraheader' && git config --local --unset-all 'http.https://github.com/.extraheader' || :"
2026-09-26T19:31:51.2968591Z [command]/usr/bin/git config --local --name-only --get-regexp ^includeIf\.gitdir:
2026-09-26T19:31:51.3010021Z [command]/usr/bin/git submodule foreach --recursive git config --local --show-origin --name-only --get-regexp remote.origin.url
2026-09-26T19:31:51.3428109Z Cleaning up orphan processes
2026-09-26T19:31:51.3732857Z ##[warning]Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: actions/checkout@v4, actions/setup-java@v4, gradle/actions/setup-gradle@v4, gradle/actions/wrapper-validation@v4. For more information see: https://github.blog/changelog/2025-09-19-deprecation-of-node-20-on-github-actions-runners/

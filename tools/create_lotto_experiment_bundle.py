"""사용자가 고정한 명세와 확인한 빌드의 소스/APK 증빙을 등록 JSON으로 묶는다.

컴파일·설치·번호 생성·평가·Git 명령은 실행하지 않는다.
APK와 소스의 대응은 --build-reference로 사용자가 확인한 빌드를 명시한다.
"""
import argparse
import hashlib
import json
from pathlib import Path


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(8192), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, type=Path)
    parser.add_argument("--product", choices=["lotto", "pension"], default="lotto")
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--split-apk", action="append", default=[], type=Path)
    parser.add_argument("--build-reference", required=True)
    parser.add_argument("--git-head", required=True, help="커밋 ID 또는 NONE; 미커밋 소스도 별도 해시 보존")
    parser.add_argument("--dirty", required=True, choices=["yes", "no"])
    parser.add_argument("--kotlin", required=True)
    parser.add_argument("--kotlin-stdlib", required=True)
    parser.add_argument("--coroutines", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    project = args.project.resolve(strict=True)
    spec = json.loads(args.spec.read_text(encoding="utf-8"))
    required = {"purpose", "historyStartRound", "targetRounds", "commonSeeds", "controlSeeds", "hypothesis", "selectionRounds", "comparisonFamilyId", "policy"}
    if not required.issubset(spec):
        raise ValueError(f"실험 명세 필수 항목 누락: {sorted(required - set(spec))}")
    paths = [
        "app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml",
        "app/src/main/java/com/habittracker/data/lotto/LottoNumberGenerator.kt",
        "app/src/main/java/com/habittracker/data/lotto/LottoPriorDesignComparison.kt",
        "app/src/main/java/com/habittracker/data/lotto/LottoDesignRunStore.kt",
        "app/src/main/java/com/habittracker/data/lotto/LottoDesignRecordJson.kt",
        "app/src/main/java/com/habittracker/data/lotto/LottoExperimentStore.kt",
        "app/src/main/java/com/habittracker/data/lotto/LottoExperimentPolicy.kt",
        "app/src/main/java/com/habittracker/data/repository/HabitRepository.kt",
        "app/src/main/java/com/habittracker/ui/lotto/LottoViewModel.kt",
        "app/src/main/java/com/habittracker/ui/lotto/LottoScreen.kt",
        "app/src/main/java/com/habittracker/ui/lotto/LottoExperimentSection.kt",
    ]
    if args.product == "pension":
        paths = [
            "app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml",
            "app/src/main/java/com/habittracker/data/lotto/PensionExperimentComparison.kt",
            "app/src/main/java/com/habittracker/data/lotto/PensionExperimentStore.kt",
            "app/src/main/java/com/habittracker/data/lotto/LottoDesignRunStore.kt",
            "app/src/main/java/com/habittracker/data/lotto/LottoDesignRecordJson.kt",
            "app/src/main/java/com/habittracker/data/lotto/LottoExperimentPolicy.kt",
            "app/src/main/java/com/habittracker/data/lotto/LotteryDrawSyncModels.kt",
            "app/src/main/java/com/habittracker/data/repository/HabitRepository.kt",
            "app/src/main/java/com/habittracker/ui/lotto/PensionLotteryGeneratorViewModel.kt",
            "app/src/main/java/com/habittracker/ui/lotto/PensionLotteryGeneratorScreen.kt",
            "app/src/main/java/com/habittracker/ui/lotto/PensionExperimentSection.kt",
        ]
    files = []
    for relative in sorted(paths):
        path = (project / relative).resolve(strict=True)
        if project not in path.parents:
            raise ValueError(f"프로젝트 밖 소스 경로: {relative}")
        content = path.read_bytes()
        files.append({"path": relative, "sha256": hashlib.sha256(content).hexdigest(), "content": content.decode("utf-8")})
    manifest = [{"path": entry["path"], "sha256": entry["sha256"]} for entry in files]
    normalized = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    splits = {}
    for split in args.split_apk:
        if split.name in splits:
            raise ValueError(f"split APK 이름 중복: {split.name}")
        splits[split.name] = file_hash(split)
    bundle = {"formatVersion": 1, "experiment": spec, "sourceEvidence": {
        "files": files, "buildSourceHash": hashlib.sha256(normalized).hexdigest(), "apkHash": file_hash(args.apk),
        "splitApkHashes": splits, "dependencyVersions": {"kotlin": args.kotlin, "kotlinStdlib": args.kotlin_stdlib, "coroutines": args.coroutines},
        "buildReference": args.build_reference, "gitHead": None if args.git_head == "NONE" else args.git_head,
        "hasUncommittedChanges": args.dirty == "yes",
    }}
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(bundle, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(args.output.resolve())


if __name__ == "__main__":
    main()

package com.habittracker.ui.lotto

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.habittracker.data.lotto.LottoDesignRecordJson
import com.habittracker.data.lotto.LottoDifferenceInterval
import com.habittracker.data.lotto.LottoExperimentDecision
import com.habittracker.ui.components.AppConfirmDialog
import com.habittracker.ui.components.AppOutlinedTextField
import com.habittracker.ui.components.AppPrimaryButton
import com.habittracker.ui.components.AppSecondaryButton
import com.habittracker.ui.components.AppSectionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun PensionExperimentSection(viewModel: PensionLotteryGeneratorViewModel) {
    val state by viewModel.experimentUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var action by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf(0) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= 16 * 1024 * 1024) { "연금 등록 문서는 최대 16MB입니다." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
                viewModel.registerExperiment(document)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { viewModel.reportExperimentImportError(error.message ?: "문서를 읽지 못했습니다.") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppSectionHeader(title = "연금 첫 실험", subtitle = "출현형 끝자리 상위 최대 3개/4개 · 추천 2+2 · 균등 대조군 4개")
        Text("운영 설정: 상위 최대 ${state.operating?.count ?: "확인 중"}개")
        Text("주 지표: 일반 당첨번호 끝 2자리 이상 연속 일치 비율. 게임·시드가 아닌 회차를 동일 가중합니다. 실패 감소는 당첨 성과 개선과 구분합니다.")
        Text("정책 미확정·설계용 결과는 채택·운영 적용에 사용할 수 없습니다. 내부 파일은 앱 데이터 삭제·재설치 시 유실될 수 있으며 해시 연결은 전체 이력의 존재를 보증하지 않습니다.")
        state.issue?.let { Text("기록 확인 실패: $it") }
        AppSecondaryButton(text = "실험·소스 증빙 JSON 등록", enabled = !state.busy, onClick = { action = "IMPORT" })
        AppSecondaryButton(text = "기록·설정 새로고침", enabled = !state.busy, onClick = viewModel::refreshExperiments)
        if (state.busy) AppSecondaryButton(text = "작업 취소", onClick = viewModel::cancelExperiment)
        if (state.operating?.approvalHash != null) AppSecondaryButton(text = "이전 승인 설정 복구", enabled = !state.busy, onClick = { reason = ""; action = "ROLLBACK" })
        if (state.experiments.isEmpty() && state.issue == null) Text("등록된 연금 실험이 없습니다. 기록 부재를 과거 미사용의 증거로 취급하지 않습니다.")
        state.experiments.forEach { run ->
            val spec = run.specification
            AppSectionHeader(title = run.id, subtitle = spec?.get("hypothesis")?.toString())
            Text("상태 ${run.state} · ${run.judgment.decision.label}: ${run.judgment.reason}")
            run.issues.forEach { Text(it) }
            Text("제외 회차 ${run.excludedRounds.sorted().joinToString()}${if (run.exclusionUncertain) " · 전체 범위 확인 불가" else " · 읽을 수 있는 기록 기준"}")
            if (spec != null) {
                val targets = LottoDesignRecordJson.list(spec.getValue("targetRounds")).map(LottoDesignRecordJson::integer)
                val seedCount = LottoDesignRecordJson.list(spec.getValue("commonSeeds")).size
                val cost = targets.size.toLong() * seedCount
                Text("${spec["purpose"]} · 대상 ${targets.joinToString()} · 공통/별도 대조군 시드 각 ${seedCount}개 · 완료 회차 ${run.rounds.size}/${targets.size}")
                Text("모델 후보 시도 상한 ${cost * 160_000}회 · 선택 ${cost * 12}번호. 시드 수는 독립 회차 수가 아닙니다.")
                if (run.state == "SUCCESS") {
                    fun mean(key: String) = run.rounds.map { LottoDesignRecordJson.decimal(it.getValue(key)) }.average()
                    Text("회차 평균: 기준 ${pensionPercent(mean("baselineRate"))} · 후보 ${pensionPercent(mean("candidateRate"))} · 대조군 ${pensionPercent(mean("controlRate"))}")
                    Text("짝지은 차이: 후보−기준 ${pensionPercent(mean("candidateBaselineDifference"))}p · 후보−대조군 ${pensionPercent(mean("candidateControlDifference"))}p")
                    pensionInterval("후보−기준 95%", run.judgment.baseline95); pensionInterval("후보−대조군 95%", run.judgment.control95)
                    pensionInterval("후보−기준 보정", run.judgment.baselineAdjusted); pensionInterval("후보−대조군 보정", run.judgment.controlAdjusted)
                    Text("전체 생성 실패율 0%. 실패·취소 실행은 부분 평균을 전체 성과로 표시하지 않습니다.")
                }
                run.failures.forEach { failure ->
                    Text("${failure["event"]} · ${failure["stage"]}\n${failure["generationContexts"]}")
                    Text("완료 모델 ${failure["successfulModelBatches"]} · 생성 실패 모델 ${failure["failedModelBatches"]} · 시도 모델 기준 실패율 ${failure["generationFailureRate"] ?: "계산 대상 아님"}")
                    Text("원인·suppressed: ${LottoDesignRecordJson.encode(failure["error"])}")
                }
                AppSecondaryButton(text = if (detailId == run.id) "근거 접기" else "설정·입력·번호·시도 횟수 보기", onClick = { detailId = if (detailId == run.id) null else run.id; page = 0 })
                if (detailId == run.id) {
                    Text("구현 ${spec["implementationId"]}\n소스 증빙 ${spec["sourceHash"]}\n등록 입력 ${spec["initialInputHash"]}\n정책 ${spec["policy"] ?: "미확정"}")
                    Text("기준 설정 ${LottoDesignRecordJson.encode(spec["baselineConfig"])}\n후보 설정 ${LottoDesignRecordJson.encode(spec["candidateConfig"])}")
                    run.rounds.drop(page * 10).take(10).forEach { round ->
                        Text("${round["targetRound"]}회 · 입력 ${round["inputHash"]} · 당첨 ${round["target"]}")
                        LottoDesignRecordJson.list(round.getValue("seeds")).forEach { raw ->
                            val seed = LottoDesignRecordJson.objectValue(raw)
                            Text("공통 ${seed["seed"]} / 대조군 ${seed["controlSeed"]}")
                            LottoDesignRecordJson.objectValue(seed.getValue("batches")).forEach { (model, rawBatch) ->
                                val batch = LottoDesignRecordJson.objectValue(rawBatch)
                                Text("$model: ${batch["numbers"]}\n끝 1/2/3자리 ${batch["suffix1Rate"]}/${batch["suffix2Rate"]}/${batch["suffix3Rate"]} · 동일 자리 ${batch["samePositionMatches"]} · 보너스 ${batch["bonusMatches"]}\n시도·필터 ${batch["diagnostics"] ?: "균등 추출"}")
                            }
                        }
                    }
                    if (page > 0) AppSecondaryButton(text = "이전 10회차", onClick = { page-- })
                    if ((page + 1) * 10 < run.rounds.size) AppSecondaryButton(text = "다음 10회차", onClick = { page++ })
                    if (run.state != "SUCCESS") run.events.filter { it["event"] == "MODEL" }.forEach { Text("부분 진단 모델: ${it["modelResult"]}") }
                }
                if (run.state in listOf("REGISTERED", "PREPARATION_FAILED")) {
                    targets.firstOrNull { it !in run.capturedRounds }?.let { target ->
                        AppSecondaryButton(text = "$target 회차 추첨 전 입력 확정", enabled = !state.busy, onClick = { viewModel.captureExperiment(run.id, target) })
                    }
                    AppSecondaryButton(text = "비교 실행 조건 확인", enabled = !state.busy, onClick = { selectedId = run.id; action = "EXECUTE" })
                }
                if (run.judgment.decision == LottoExperimentDecision.ADOPTION_CANDIDATE) AppPrimaryButton(text = "근거 확인 후 승인 적용", enabled = !state.busy, onClick = { selectedId = run.id; reason = ""; action = "APPLY" })
            }
        }
    }
    val selected = state.experiments.singleOrNull { it.id == selectedId }
    action?.let { command ->
        AppConfirmDialog(title = when (command) { "IMPORT" -> "연금 실험 등록"; "EXECUTE" -> "연금 비교 실행"; "APPLY" -> "연금 설정 승인 적용"; else -> "이전 연금 설정 복구" },
            confirmText = if (command == "IMPORT") "문서 선택" else "확인", onDismiss = { action = null },
            message = when (command) {
                "IMPORT" -> "회차·시드·용도·정책과 소스/APK 대응을 확인한 문서를 등록합니다. 정책 미확정은 null로 유지하며 등록 이후 변경은 새 실험으로 구분합니다."
                "EXECUTE" -> selected?.specification?.let { "대상 ${it["targetRounds"]}, 공통 ${it["commonSeeds"]}, 별도 대조군 ${it["controlSeeds"]}. 위에 표시한 계산 상한으로 3개 모델 각 4번호를 비교합니다. 실패·취소도 기록하며 중단 계산을 재실행하지 않습니다. 운영 설정은 유지합니다." }
                "APPLY" -> "최종 근거를 확인하고 출현형 끝자리 후보 상위 최대 4개를 승인합니다. 기존 생성·구매·로또 기록은 보존합니다."
                else -> "직전 변경의 이전 승인 설정으로 복구합니다. 생성·평가 기록은 덮어쓰지 않습니다."
            }, content = if (command in listOf("APPLY", "ROLLBACK")) ({ AppOutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("승인·복구 이유") }) }) else null,
            onConfirm = { when (command) { "IMPORT" -> importer.launch(arrayOf("application/json", "text/plain")); "EXECUTE" -> selectedId?.let(viewModel::executeExperiment)
                "APPLY" -> selectedId?.let { viewModel.approveExperiment(it, reason) }; "ROLLBACK" -> viewModel.rollbackExperimentSetting(reason) }; action = null })
    }
}

@Composable
private fun pensionInterval(label: String, interval: LottoDifferenceInterval?) { interval?.let { Text("$label: ${pensionPercent(it.estimate)}p [${pensionPercent(it.lower)}p, ${pensionPercent(it.upper)}p]") } }
private fun pensionPercent(value: Double) = String.format(Locale.ROOT, "%.3f%%", value * 100)

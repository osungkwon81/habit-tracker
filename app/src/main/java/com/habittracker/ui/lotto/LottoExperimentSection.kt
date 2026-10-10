package com.habittracker.ui.lotto

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun LottoExperimentSection(viewModel: LottoViewModel) {
    val state by viewModel.experimentUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailPage by rememberSaveable { mutableStateOf(0) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 16 * 1024 * 1024) { "실험 문서가 16MB 제한을 초과합니다." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }
                viewModel.registerExperiment(document)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { viewModel.reportExperimentImportError(error.message ?: "실험 문서를 읽지 못했습니다.") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        AppSectionHeader(title = "로또 첫 실험", subtitle = "균형형 · BASIC · 모델별 5게임 · priorDraws 32/64 및 균등 대조군")
        Text("현재 균형형 BASIC 5게임 승인 설정: priorDraws ${state.operating?.priorDraws ?: "확인 중"}")
        Text("정책을 확정하지 않아도 비교 결과를 확인할 수 있습니다. 설계용 결과와 정책 미확정 결과는 운영 적용에 사용할 수 없습니다.")
        Text("내부 파일은 데이터 삭제·재설치 시 유실될 수 있습니다. 해시 연결은 전체 이력의 존재를 보증하지 않습니다.")
        AppSecondaryButton(text = "실험·소스 증빙 JSON 가져오기", enabled = !state.busy, onClick = { confirmation = "IMPORT" })
        AppSecondaryButton(text = "기록·설정 새로고침", enabled = !state.busy, onClick = viewModel::refreshExperiments)
        if (state.busy) AppSecondaryButton(text = "진행 작업 취소", onClick = viewModel::cancelExperiment)
        if (state.operating?.approval != null) AppSecondaryButton(text = "이전 승인 설정 복구", enabled = !state.busy, onClick = {
            reason = ""; confirmation = "ROLLBACK"
        })
        if (state.experiments.isEmpty()) Text("등록한 실험이 없습니다. 회차·시드·정책·소스 증빙이 담긴 등록 문서를 가져오세요.")
        state.experiments.forEach { experiment ->
            AppSectionHeader(title = experiment.id, subtitle = experiment.specification["hypothesis"]?.toString())
            Text("${experiment.specification["purpose"]} · 대상 ${experiment.targetRounds.joinToString()} · 시드 ${experiment.seedCount}개")
            Text("점수 계산 ${experiment.scoreCount}개 · 후보 생성 시도 상한 ${experiment.scoreCount * 20}회 · 선택 ${experiment.selectedGameCount}게임")
            Text("${experiment.judgment.decision.label}: ${experiment.judgment.reason}")
            Text("주 지표는 본번호 3개 이상 게임 비율의 회차 동일 가중 평균입니다. 커버리지·중복 분산은 당첨 예측력과 구분합니다.")
            experiment.issue?.let { Text(it) }
            if (experiment.specification["purpose"] == "FINAL" && !experiment.executionStarted) {
                Text("추첨 전 입력 기록 ${experiment.capturedInputRounds.size}/${experiment.targetRounds.size}회차. 기록의 존재와 무결성 검증은 구분하며 실행 전 전체 입력을 확인합니다.")
                experiment.targetRounds.firstOrNull { it !in experiment.capturedInputRounds }?.let { target ->
                    AppSecondaryButton(text = "${target}회차 추첨 전 입력 확정", enabled = !state.busy,
                        onClick = { viewModel.captureExperimentInputs(experiment.id, target) })
                }
            }
            val run = experiment.run
            if (run != null) {
                Text("기록 상태 ${run.state} · 독립 추첨 ${experiment.judgment.independentRounds}회 · 시드는 회차 내부 반복입니다.")
                if (run.baselineRate != null && experiment.issue == null) {
                    Text("기준 ${percent(run.baselineRate!!)} · 후보 ${percent(run.candidateRate!!)} · 대조군 ${percent(run.diagnosticRounds.mapNotNull { it.controlRate }.average())}")
                    Text("후보−기준 ${percent(run.pairedRateDifference!!)}p")
                }
                intervalText("후보−기준 원래 95%", experiment.judgment.baseline95)
                intervalText("후보−대조군 원래 95%", experiment.judgment.control95)
                intervalText("후보−기준 보정", experiment.judgment.baselineAdjusted)
                intervalText("후보−대조군 보정", experiment.judgment.controlAdjusted)
                AppSecondaryButton(text = if (detailsId == experiment.id) "상세 근거 접기" else "회차 번호·설정·입력 근거 보기", onClick = {
                    detailsId = if (detailsId == experiment.id) null else experiment.id; detailPage = 0
                })
                if (detailsId == experiment.id) {
                    Text("기준 설정 ${experiment.specification["baselineConfigHash"]}\n후보 설정 ${experiment.specification["candidateConfigHash"]}\n구현 ${experiment.specification["implementationId"]}")
                    Text("등록 입력 ${experiment.specification["inputAtRegistrationHash"]}\n정책 ${experiment.specification["policy"] ?: "미확정"}")
                    run.diagnosticRounds.drop(detailPage * 10).take(10).forEach { round ->
                        Text("${round.targetRoundNo}회: 기준 ${percent(round.baselineRate)} · 후보 ${percent(round.candidateRate)} · 대조군 ${round.controlRate?.let(::percent) ?: "없음"} · 차이 ${percent(round.pairedRateDifference)}p")
                        Text("입력 ${round.inputDataHash}\n정답 ${round.targetDataHash}")
                        round.seeds.forEach { seed ->
                            Text("공통 시드 ${seed.seed} · 대조군 시드 ${seed.controlSeed}")
                            listOf("기준" to seed.baseline, "후보" to seed.candidate, "대조군" to seed.control).forEach { (label, batch) ->
                                batch?.let { Text("$label: ${it.games.joinToString(" / ") { game -> game.joinToString(",") }}\n일치 ${it.mainMatchCounts} · 커버리지 ${it.distinctNumberCount} · 재사용 ${it.reusedNumberSlots} · 게임쌍 겹침 ${it.pairOverlapCounts}") }
                            }
                        }
                    }
                    if (detailPage > 0) AppSecondaryButton(text = "이전 10회차", onClick = { detailPage-- })
                    if ((detailPage + 1) * 10 < run.diagnosticRounds.size) AppSecondaryButton(text = "다음 10회차", onClick = { detailPage++ })
                }
            }
            if (run == null && !experiment.executionStarted && experiment.specification["purpose"] != "INVALID") AppSecondaryButton(text = "실행 조건 확인", enabled = !state.busy, onClick = {
                selectedId = experiment.id; confirmation = "EXECUTE"
            })
            if (experiment.judgment.decision == LottoExperimentDecision.ADOPTION_CANDIDATE) AppPrimaryButton(text = "근거 확인 후 승인 적용", enabled = !state.busy, onClick = {
                selectedId = experiment.id; reason = ""; confirmation = "APPLY"
            })
        }
    }
    val selected = state.experiments.singleOrNull { it.id == selectedId }
    confirmation?.let { action ->
        AppConfirmDialog(
            title = when (action) { "IMPORT" -> "실험 명세 등록"; "EXECUTE" -> "비교 실행 확인"; "APPLY" -> "운영 설정 승인"; else -> "이전 설정 복구" },
            confirmText = if (action == "IMPORT") "문서 선택" else "확인",
            onDismiss = { confirmation = null },
            message = when (action) {
                "IMPORT" -> "이 문서의 정책·구간·시드 및 소스 원문과 APK의 대응을 확인했습니까? 등록 후 변경은 새 실험으로 구분합니다. 미확정 정책은 null로 남겨 주세요."
                "EXECUTE" -> selected?.let { "${it.targetRounds.joinToString()}회차, 공통 시드 ${it.seedCount}개와 별도 대조군 시드 ${it.seedCount}개. 점수 ${it.scoreCount}개, 생성 시도 상한 ${it.scoreCount * 20}회, 선택 ${it.selectedGameCount}게임을 계산합니다. 시작 후 실패·취소도 기록하며 자동 재실행하지 않습니다. 운영 설정은 변경하지 않습니다." }
                "APPLY" -> "표시된 최종 평가 근거로 균형형 BASIC 5게임의 priorDraws 64를 적용합니다. 다른 모드·연금·분산형 설정은 유지합니다."
                else -> "직전 변경의 이전 승인 설정을 복구합니다. 생성·평가 이력은 보존됩니다."
            },
            content = if (action in listOf("APPLY", "ROLLBACK")) ({
                AppOutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("승인·복구 이유") })
            }) else null,
            onConfirm = {
                when (action) {
                    "IMPORT" -> importer.launch(arrayOf("application/json", "text/plain"))
                    "EXECUTE" -> selectedId?.let(viewModel::executeExperiment)
                    "APPLY" -> selectedId?.let { viewModel.approveExperiment(it, reason) }
                    "ROLLBACK" -> viewModel.rollbackExperimentSetting(reason)
                }
                confirmation = null
            },
        )
    }
}

@Composable
private fun intervalText(label: String, interval: LottoDifferenceInterval?) {
    interval?.let { Text("$label: ${percent(it.estimate)}p [${percent(it.lower)}p, ${percent(it.upper)}p]") }
}
private fun percent(value: Double) = String.format(Locale.ROOT, "%.3f%%", value * 100)

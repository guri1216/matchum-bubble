package com.matchum.bubble

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** 설정 화면: 권한 안내, 켜기/끄기, 테스트, AI 키 입력 */
class MainActivity : Activity() {

    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener { openServiceSettings() }

        val swEnabled = findViewById<Switch>(R.id.swEnabled)
        swEnabled.isChecked = Prefs.enabled(this)
        swEnabled.setOnCheckedChangeListener { _, v -> Prefs.setEnabled(this, v) }

        // 규칙 테스트
        val etTest = findViewById<EditText>(R.id.etTest)
        val tvTest = findViewById<TextView>(R.id.tvTestResult)
        etTest.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (text.isBlank()) { tvTest.text = ""; return }
                val issues = RuleChecker.check(text)
                tvTest.text = if (issues.isEmpty()) {
                    "찾은 오류 없음 👍"
                } else {
                    issues.joinToString("\n") { "• ${it.original} → ${it.replacement}  (${it.reason})" } +
                        "\n\n고친 문장: " + RuleChecker.apply(text, issues)
                }
            }
        })

        // AI 설정
        val swAi = findViewById<Switch>(R.id.swAi)
        val aiPanel = findViewById<LinearLayout>(R.id.aiPanel)
        val etKey = findViewById<EditText>(R.id.etApiKey)
        val etModel = findViewById<EditText>(R.id.etModel)
        val tvAi = findViewById<TextView>(R.id.tvAiResult)

        swAi.isChecked = Prefs.aiEnabled(this)
        aiPanel.visibility = if (swAi.isChecked) View.VISIBLE else View.GONE
        swAi.setOnCheckedChangeListener { _, v ->
            Prefs.setAiEnabled(this, v)
            aiPanel.visibility = if (v) View.VISIBLE else View.GONE
        }
        etKey.setText(Prefs.apiKey(this))
        etModel.setText(Prefs.model(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.setApiKey(this, etKey.text.toString().trim())
            Prefs.setModel(this, etModel.text.toString().trim())
            Toast.makeText(this, "저장했어요", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnTestAi).setOnClickListener {
            val text = etTest.text.toString()
            val key = etKey.text.toString().trim()
            val model = etModel.text.toString().trim().ifBlank { Prefs.DEFAULT_MODEL }
            if (text.isBlank()) { tvAi.text = "위 테스트 칸에 문장을 먼저 써 주세요."; return@setOnClickListener }
            if (key.isBlank()) { tvAi.text = "API 키를 입력해 주세요."; return@setOnClickListener }
            tvAi.text = "✨ 검사하는 중…"
            Thread {
                val result = runCatching { AiChecker.correct(key, model, text) }
                runOnUiThread {
                    tvAi.text = result.fold(
                        onSuccess = { "AI 결과: $it" },
                        onFailure = { "실패: ${it.message}" }
                    )
                }
            }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isServiceOn()) {
            tvStatus.text = "✅ 켜져 있어요. 카톡이나 문자에서 써 보세요!"
            tvStatus.setTextColor(0xFF1A8F3C.toInt())
        } else {
            tvStatus.text = "⚠️ 아직 꺼져 있어요. 아래 버튼으로 켜 주세요."
            tvStatus.setTextColor(0xFFD14343.toInt())
            if (!askedThisTime) {
                askedThisTime = true
                showPermissionGuide()
            }
        }
    }

    private var askedThisTime = false

    /** 앱을 열었는데 권한이 꺼져 있으면 안내창 → 확인 누르면 스위치 화면으로 바로 이동 */
    private fun showPermissionGuide() {
        AlertDialog.Builder(this)
            .setTitle("권한 하나만 켜 주세요")
            .setMessage(
                "입력창 위에 맞춤법 말풍선을 띄우려면 '접근성' 권한이 필요해요.\n\n" +
                    "확인을 누르면 설정 화면으로 이동해요. 거기서 '맞춤법 말풍선' 스위치를 켜고 '허용'을 누른 뒤 뒤로 가기로 돌아오세요.\n\n" +
                    "처음 한 번만 하면 되고, 키보드는 따로 설정할 필요 없어요."
            )
            .setPositiveButton("확인") { _, _ -> openServiceSettings() }
            .setNegativeButton("나중에", null)
            .show()
    }

    /**
     * 이 앱의 접근성 스위치 화면으로 바로 이동.
     * 지원하지 않는 폰이면 접근성 메뉴로 이동하고, 가능하면 이 앱 항목을 강조 표시.
     */
    private fun openServiceSettings() {
        val cn = ComponentName(this, SpellService::class.java).flattenToString()

        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
        if (tryStart(details)) {
            toast("'맞춤법 말풍선' 스위치를 켜 주세요")
            return
        }

        val args = Bundle().apply { putString(":settings:fragment_args_key", cn) }
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", cn)
            .putExtra(":settings:show_fragment_args", args)
        if (tryStart(list)) {
            toast("'설치된 앱' → '맞춤법 말풍선'을 켜 주세요")
        } else {
            toast("설정 > 접근성에서 '맞춤법 말풍선'을 켜 주세요")
        }
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun isServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val cn = ComponentName(this, SpellService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }
}

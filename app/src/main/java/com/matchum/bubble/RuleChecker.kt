package com.matchum.bubble

/** 찾아낸 맞춤법 오류 하나 (text[start, end) 를 replacement 로 바꾸면 됨) */
data class Issue(
    val start: Int,
    val end: Int,
    val original: String,
    val replacement: String,
    val reason: String,
)

private class Rule(pattern: String, val reason: String, val fix: (MatchResult) -> String?) {
    val regex = Regex(pattern)
}

/**
 * 휴대폰 안에서 바로 돌아가는 규칙 기반 맞춤법 검사기.
 * 채팅에서 자주 틀리는 표현 위주로, 잘못 고칠 위험이 적은 규칙만 넣었습니다.
 * 규칙을 추가하고 싶으면 아래 rules 목록에 한 줄씩 더하면 됩니다.
 */
object RuleChecker {

    /** 받침이 'ㄹ'인 한글 글자인지 (예: 할, 갈, 줄) */
    private fun hasRieulFinal(c: Char): Boolean {
        val code = c.code - 0xAC00
        return code in 0..11171 && code % 28 == 8
    }

    private fun simple(from: String, to: String, reason: String) =
        Rule(Regex.escape(from), reason) { to }

    // 문장 끝 (글 끝, 줄바꿈, 문장부호, ㅋㅋ/ㅎㅎ/ㅠㅠ 등)
    private const val END = "(?=$|[\\n.!?~ㅋㅎㅠㅜ^])"

    private val rules: List<Rule> = listOf(
        // ── 되 / 돼 ──
        simple("되요", "돼요", "'되어요'를 줄이면 '돼요'예요."),
        Rule("됬", "'되었-'을 줄이면 '됐-'이에요.") { "됐" },
        simple("되서", "돼서", "'되어서'를 줄이면 '돼서'예요."),
        Rule("되도(?!록)", "'되어도'를 줄이면 '돼도'예요.") { "돼도" },
        Rule("안\\s?되$END", "'안 되어'를 줄이면 '안 돼'예요.") { "안 돼" },
        Rule("되$END", "문장 끝에서는 '되어'를 줄인 '돼'를 써요.") { "돼" },
        Rule("돼(?=[고는면니기겠])", "'되-' 뒤에 바로 '-고, -는, -면'이 붙을 땐 '되'를 써요.") { "되" },
        Rule("됀", "'된'이 맞아요.") { "된" },
        Rule("됄", "'될'이 맞아요.") { "될" },
        simple("뵈요", "봬요", "'뵈어요'를 줄이면 '봬요'예요."),
        Rule("뵜", "'뵈었-'을 줄이면 '뵀-'이에요.") { "뵀" },

        // ── 안 / 않 ──
        Rule("않\\s?(?=[하해했할돼되가갈와왔먹줘])", "'아니'를 줄인 말은 '안'이에요. (예: 안 해, 안 돼)") { "안 " },
        Rule("지\\s안(?=아|았|고|을|는|습)", "'-지' 뒤에는 '않-'을 써요. (예: 하지 않아)") { "지 않" },

        // ── 자주 틀리는 단어 ──
        Rule("몇\\s?일", "'몇 일'이 아니라 '며칠'이 표준어예요.") { "며칠" },
        simple("어떻해", "어떡해", "'어떻게 해'를 줄이면 '어떡해'예요."),
        simple("어떡게", "어떻게", "'어떻게'가 맞아요."),
        simple("왠만", "웬만", "'웬만하다'가 맞아요."),
        simple("왠일", "웬일", "'어찌 된 일'이라는 뜻은 '웬일'이에요."),
        simple("웬지", "왠지", "'왜인지'를 줄이면 '왠지'예요."),
        simple("금새", "금세", "'금시에'를 줄이면 '금세'예요."),
        simple("오랫만", "오랜만", "'오래간만'을 줄이면 '오랜만'이에요."),
        simple("역활", "역할", "'역할'이 맞아요."),
        simple("희안", "희한", "'희한하다'가 맞아요."),
        simple("어의없", "어이없", "'어이없다'가 맞아요."),
        simple("설겆이", "설거지", "'설거지'가 표준어예요."),
        simple("일부로", "일부러", "'고의로'라는 뜻이면 '일부러'예요."),
        simple("들어나", "드러나", "'보이게 되다'라는 뜻은 '드러나다'예요."),
        simple("구지", "굳이", "'굳이'가 맞아요."),
        simple("갯수", "개수", "'개수'가 맞아요. (사이시옷 없음)"),
        simple("떡볶기", "떡볶이", "'떡볶이'가 맞아요."),
        simple("아니예요", "아니에요", "'아니다'에는 '-에요'가 붙어요."),
        simple("깨끗히", "깨끗이", "'깨끗이'가 맞아요."),
        simple("일일히", "일일이", "'일일이'가 맞아요."),
        simple("곰곰히", "곰곰이", "'곰곰이'가 맞아요."),
        simple("꼼꼼이", "꼼꼼히", "'꼼꼼히'가 맞아요."),
        simple("솔직이", "솔직히", "'솔직히'가 맞아요."),
        Rule("바램(?=[이은을도])", "'바라다'의 명사는 '바람'이에요.") { "바람" },
        Rule("예기(?=[했하해를가도])", "'이야기'를 줄이면 '얘기'예요.") { "얘기" },

        // ── -려고 ──
        simple("할려고", "하려고", "'-려고' 앞에 'ㄹ'을 넣지 않아요."),
        simple("할려면", "하려면", "'-려면' 앞에 'ㄹ'을 넣지 않아요."),
        simple("갈려고", "가려고", "'-려고' 앞에 'ㄹ'을 넣지 않아요."),
        simple("볼려고", "보려고", "'-려고' 앞에 'ㄹ'을 넣지 않아요."),
        simple("줄려고", "주려고", "'-려고' 앞에 'ㄹ'을 넣지 않아요."),
        simple("먹을려고", "먹으려고", "'-려고' 앞에 'ㄹ'을 넣지 않아요."),

        // ── 거 / 게 / 걸 ──
        Rule("(?<![가-힣])(내|니|너|제)꺼", "'것'을 줄인 말은 '거'예요. (예: 내 거)") { m ->
            when (m.groupValues[1]) {
                "내" -> "내 거"
                "니" -> "네 거"
                "너" -> "너 거"
                else -> "제 거"
            }
        },
        Rule("([가-힣])께(?=요|$|[^가-힣])", "약속을 나타낼 땐 '-ㄹ게'예요. (예: 할게)") { m ->
            val c = m.groupValues[1][0]
            if (hasRieulFinal(c) && c != '들') "${c}게" else null
        },
        Rule("([가-힣])꺼(?=야|예요|에요|지|다|라|면|니|든|잖|임)", "'것'을 줄인 말은 '거'예요. (예: 할 거야)") { m ->
            val c = m.groupValues[1][0]
            if (hasRieulFinal(c) && c != '들') "$c 거" else null
        },
        Rule("([가-힣])껄(?=요|$|[^가-힣])", "'-ㄹ걸'이 맞아요. (예: 할걸)") { m ->
            val c = m.groupValues[1][0]
            if (hasRieulFinal(c)) "${c}걸" else null
        },
    )

    /** 오류 목록 (겹치는 건 앞쪽 하나만) */
    fun check(text: String): List<Issue> {
        val found = mutableListOf<Issue>()
        for (rule in rules) {
            for (m in rule.regex.findAll(text)) {
                val rep = rule.fix(m) ?: continue
                if (rep == m.value) continue
                found += Issue(m.range.first, m.range.last + 1, m.value, rep, rule.reason)
            }
        }
        found.sortWith(compareBy<Issue> { it.start }.thenByDescending { it.end })
        val result = mutableListOf<Issue>()
        var lastEnd = -1
        for (issue in found) {
            if (issue.start >= lastEnd) {
                result += issue
                lastEnd = issue.end
            }
        }
        return result
    }

    /** 오류들을 고친 문장 */
    fun apply(text: String, issues: List<Issue>): String {
        val sb = StringBuilder(text)
        for (i in issues.sortedByDescending { it.start }) {
            sb.replace(i.start, i.end, i.replacement)
        }
        return sb.toString()
    }
}

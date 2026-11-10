#!/usr/bin/env python3
"""Single source of the app copy (zh / en). Copy comes word for word from the design package (copy.hints,
copy.lessonTaglines, copy.results, panelContents, contentInventory names); lines the design did not write follow
content.tone (spoken, short, like a coach next to you; no 您, no exclamation piles). Writes res/values/strings.xml
(English, default) and res/values-zh/strings.xml.   python3 tools/strings_table.py"""
import os
from xml.sax.saxutils import escape

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
S = [
    # key, zh, en
    ("app_name", "视距飞手", "Line-of-Sight Pilot"),
    ("starting", "准备中…", "Getting ready..."),
    # S01 welcome (panelContents.welcomePage)
    ("welcome_1", "左手柄摇杆 = 油门和机头，右手柄 = 前后左右，就是 Mode 2 的两根杆", "Left stick: throttle + yaw. Right stick: pitch + roll. Mode 2."),
    ("welcome_1_nc", "没检测到手柄：用面板上的两块摇杆垫，捏住拖就行", "No controllers found: pinch and drag the stick pads."),
    ("welcome_2", "飞机是真实大小，在你地板上方飞，远近高低用眼睛看", "Life-size drone over your real floor. Judge it by eye."),
    ("welcome_3", "练手感用的，不是培训课时，考试还是得去报名", "For practice only. Not training hours."),
    ("welcome_ok", "好，开始", "OK, let's go"),
    # copy.hints
    ("h_S02", "正在找地板…", "Looking for your floor..."),
    ("h_S02timeout", "没找到地板，先用估计高度 · 设置里可调", "No floor found, using a guess · adjust in Settings"),
    ("h_S03", "手柄指着地板，场地跟着走 · 左杆左右转", "Point at the floor to move the course · left stick turns it"),
    ("h_S03noController", "看着地板捏一下放场地 · 左垫左右转", "Look at the floor and pinch to place · left pad turns it"),
    ("h_S05_L1", "左杆慢慢上推起飞 · 看影子判高度", "Ease the left stick up to take off · watch the shadow"),
    ("h_S05_L2", "左杆左右把机头转过来对着你", "Left stick left/right: turn the nose to face you"),
    ("h_S05_L3", "在框里慢慢转，左杆推一点点就行", "Turn slowly in the box, a little left stick is enough"),
    ("h_S05_L4", "先看地上的 8 字，再起飞", "Look at the 8 on the floor first, then take off"),
    ("h_S05_L5", "先上到 1.5 m，再慢慢落垫子里", "Up to 1.5 m, then ease it down onto the pad"),
    ("h_S05_L6", "一口气做完五项，中间别落地", "All five in one go, no landing in between"),
    ("h_S05_L7", "姿态模式：松杆它不停，迎着风压点杆", "ATTI: it won't stop when you let go, lean into the wind"),
    ("h_S05_F1", "随便飞，没人打分", "Just fly, nobody's scoring"),
    ("h_S05_F2", "门亮哪个穿哪个", "Fly through whichever gate is lit"),
    ("h_noseIn", "机头对着你了：右杆往右，它往你左边走", "Nose-in: push right, it goes to YOUR left"),
    ("h_hoverIn", "在框里了，稳住", "In the box, hold it"),
    ("h_hoverOut", "出框了，小杆量回来", "Out of the box, small stick back in"),
    ("h_done", "好了，落回垫子", "Done. Bring it back down to the pad"),
    ("h_L4", "绕桩：左杆转头 + 右杆前推，一起来", "Round the pole: yaw left stick + push right stick"),
    ("h_L5", "最后 30 cm 收油门，慢", "Last 30 cm: ease off, go slow"),
    ("h_S06a", "飞出场地了，往回飞", "Out of the field, fly back"),
    ("h_S07_soft", "落得不错", "Nice landing"),
    ("h_S07_heavy", "落得有点重，下次早点收", "A bit hard, ease off sooner"),
    ("h_S09", "落重了 · 飞机回垫子", "Too hard · back to the pad"),
    ("h_S15", "暂停中 · 松杆也行", "Paused · you can let go"),
    ("h_S10", "穿过亮着的那个门", "Fly through the lit gate"),
    ("h_atti", "姿态模式：松杆它不会停", "ATTI mode: it won't stop when you let go"),
    ("h_nearYou", "飞机在你身边了", "The drone is right next to you"),
    ("h_chainNext", "好，下一项：%1$s", "Good. Next: %1$s"),
    ("h_noseHint", "机头跟着路走：左杆一起转", "Keep the nose on the path: add yaw"),
    # buttons
    ("place_here", "放在这里", "Put it here"),
    ("takeoff", "起飞", "Take off"),
    ("pause", "暂停", "Pause"),
    ("resume", "继续", "Resume"),
    ("retry", "重飞", "Fly again"),
    ("next_lesson", "下一科", "Next"),
    ("next_round", "下一轮", "Next round"),
    ("to_picker", "回选科", "Lessons"),
    ("prev_cd", "上一科", "Previous lesson"),
    ("next_cd", "下一科", "Next lesson"),
    ("settings", "设置", "Settings"),
    # v0.1.2 main window: system TabBar pages (D21 / D8), the Stage chip that reopens a closed window
    ("tab_fly", "飞行", "Fly"),
    ("tab_lessons", "科目", "Lessons"),
    ("open_panel", "打开面板", "Open panel"),
    # q4-3: the main window waits for a level head (D9); shown on the Stage meanwhile
    ("look_level", "平视前方", "Look straight ahead"),
    ("look_level_2", "面板就出来", "and the panel opens"),
    ("done_btn", "好了", "Done"),
    ("mute", "静音", "Mute"),
    ("vsticks", "虚拟摇杆", "Stick pads"),
    ("dir_hints", "方向提示", "Direction hints"),
    ("key_pause", "A 键 = 暂停", "A = pause"),
    ("key_pause_nc", "捏主按钮 = 暂停", "Main button = pause"),
    # chips
    ("chip_ready", "待起飞", "Ready"),
    ("chip_box", "框内 %1$d / %2$d s", "In box %1$d/%2$d s"),
    ("chip_spin", "转了 %1$d°", "Turned %1$d°"),
    ("chip_gate", "第 %1$d / %2$d 门", "Gate %1$d/%2$d"),
    ("chip_landing", "落地中", "Landing"),
    ("chip_landed", "着陆", "Landed"),
    ("chip_free", "自由飞", "Free flight"),
    ("chip_full", "全套 · 5 科", "Full run · 5"),
    ("chip_chain", "%1$d/5 · %2$s", "%1$d/5 · %2$s"),
    ("chip_round", "第 %1$d 轮 · 门 %2$d / %3$d · %4$s", "R%1$d · %2$d/%3$d · %4$s"),
    ("chip_paused", "暂停", "Paused"),
    ("chip_up", "上到 %1$.1f m", "Up to %1$.1f m"),
    # mode / wind / tolerance
    ("mode_gps", "GPS", "GPS"),
    ("mode_atti", "姿态", "ATTI"),
    ("wind_none", "无", "Calm"),
    ("wind_light", "微风", "Breeze"),
    ("wind_gusty", "阵风", "Gusty"),
    ("tol_lenient", "宽松", "Easy"),
    ("tol_standard", "标准", "Standard"),
    ("tol_label", "判定", "Judging"),
    # lessons (contentInventory.lessons zh / en)
    ("l1_name", "起飞 · 对尾悬停", "Takeoff · Tail-in hover"),
    ("l2_name", "对头悬停", "Nose-in hover"),
    ("l3_name", "自旋一圈", "Slow 360"),
    ("l4_name", "水平 8 字", "Figure eight"),
    ("l5_name", "定点降落", "Spot landing"),
    ("l6_name", "全套串联", "Full run"),
    ("l7_name", "姿态模式悬停", "ATTI hover"),
    ("f1_name", "自由飞", "Free flight"),
    ("f2_name", "航点挑战", "Waypoint run"),
    # copy.lessonTaglines
    ("l1_tag", "它背对你，最听话的时候", "Tail toward you: the easy one"),
    ("l2_tag", "它看着你，左右就反了", "Nose toward you: left is right"),
    ("l3_tag", "转一圈，杆的方向跟着变", "One slow circle, the sticks keep changing"),
    ("l4_tag", "两根杆一起来", "Both sticks, together"),
    ("l5_tag", "慢慢放下去", "Set it down gently"),
    ("l6_tag", "考试怎么走就怎么飞", "Fly it like the test"),
    ("l7_tag", "松杆它不停", "Let go and it keeps going"),
    ("f1_tag", "随便飞，没人打分", "Just fly, nobody's scoring"),
    ("f2_tag", "穿门，一轮比一轮难", "Rings, a bit harder each round"),
    # L6 chain items
    ("task_tail", "对尾", "Tail-in"),
    ("task_nose", "对头", "Nose-in"),
    ("task_spin", "自旋", "360"),
    ("task_eight", "8 字", "Eight"),
    ("task_land", "降落", "Landing"),
    # picker
    ("picker_title", "选一科", "Pick one"),
    ("replace", "重摆场地", "Move course"),
    ("best_s", "最好 %1$d s ✓", "Best %1$d s ✓"),
    ("best_s_open", "最好 %1$d s", "Best %1$d s"),
    ("best_deg", "最好 %1$d°", "Best %1$d°"),
    ("best_gates", "最好 %1$d 门", "Best %1$d gates"),
    ("best_off", "最好偏 %1$.1f m ✓", "Best %1$.1f m off ✓"),
    ("best_time", "最好 %1$s ✓", "Best %1$s ✓"),
    ("passed", "过了 ✓", "Passed ✓"),
    ("not_yet", "还没飞", "Not yet"),
    ("round_n", "第 %1$d 轮", "Round %1$d"),
    ("use_controller", "建议用手柄", "Controllers recommended"),
    # results (copy.results)
    ("res_pass", "过 ✓", "Pass"),
    ("res_near", "差一点", "Almost"),
    ("res_retry", "再来", "Again"),
    ("d_high", "高了 %1$.1f m", "%1$.1f m too high"),
    ("d_low", "低了 %1$.1f m", "%1$.1f m too low"),
    ("d_outCount", "出框 %1$d 次", "Left the box %1$d×"),
    ("d_spinShort", "少转了 %1$d°", "%1$d° short"),
    ("d_gateMissed", "第 %1$d 门没过", "Missed gate %1$d"),
    ("d_landOff", "落在垫外 %1$.1f m", "%1$.1f m off the pad"),
    ("d_landHard", "落重了", "Hard landing"),
    ("d_landHeavy", "落得重", "Landed a bit hard"),
    ("d_timeout", "超时了（%1$s）", "Out of time (%1$s)"),
    ("d_noseOff", "机头没跟上路线", "Nose didn't follow the path"),
    ("d_hoverSum", "框内 %1$d s · 最高偏 %2$.1f m", "%1$d s in the box · max %2$.1f m off"),
    ("d_spinSum", "转了 %1$d°，位置没出框", "Turned %1$d°, stayed in the box"),
    ("d_landSum", "离垫心 %1$.1f m", "%1$.1f m from the centre"),
    ("d_time", "用时 %1$s", "Time %1$s"),
    ("res_meta", "%1$s · %2$s · 风 %3$s", "%1$s · %2$s · wind %3$s"),
    ("loosened", "连着差一点两次，判定放宽了一点", "Two near misses, so it's a little easier now"),
    ("res_small", "科目名对照 CAAC 视距内实践考试，场地按客厅缩放，不算培训课时", "Names follow the CAAC VLOS test. Course scaled to a living room. Not training hours."),
    # altitude tag
    ("tag_tail", "对尾", "Tail-in"),
    ("tag_nose", "对头", "Nose-in"),
    ("tag_side", "侧向", "Side"),
    ("tag_wind", "↗ 风", "↗ wind"),
    ("tag_off", "偏 %1$.1f m", "%1$.1f m off"),
    # virtual sticks
    ("pad_left", "↕ 油门 · ↔ 机头", "↕ throttle · ↔ yaw"),
    ("pad_right", "↕ 前后 · ↔ 左右", "↕ pitch · ↔ roll"),
    # 3D direction hint labels
    ("dir_right", "右杆→", "R stick →"),
    ("dir_up", "右杆↑", "R stick ↑"),
    # settings (panelContents.settingsPage)
    ("set_title", "设置", "Settings"),
    ("set_volume", "音量", "Volume"),
    ("vol_low", "低", "Low"),
    ("vol_mid", "中", "Mid"),
    ("vol_high", "高", "High"),
    ("set_mode", "默认模式", "Default mode"),
    ("set_wind", "风", "Wind"),
    ("set_tol", "容差", "Judging"),
    ("set_pole", "桩距", "Pole gap"),
    ("set_pole_note", "桩距大了，左边的门会在面板后面，面板会变淡", "Wider gap: the left gate sits behind the panel, so it fades"),
    ("set_hover", "悬停高", "Hover height"),
    ("set_ceiling", "天花板", "Ceiling"),
    ("set_floor", "地面", "Floor"),
    ("floor_found", "已识别地板", "Floor found"),
    ("floor_est", "估计 · 比头低 %1$.1f m", "Guessed · %1$.1f m below your head"),
    ("sit", "我坐着", "I'm sitting"),
    ("stand", "我站着", "I'm standing"),
    ("set_invert", "摇杆反向", "Invert sticks"),
    ("inv_ly", "左杆 ↕", "Left ↕"),
    ("inv_lx", "左杆 ↔", "Left ↔"),
    ("inv_ry", "右杆 ↕", "Right ↕"),
    ("inv_rx", "右杆 ↔", "Right ↔"),
    ("set_slow", "飞得慢一点", "Fly slower"),
    ("set_hints", "方向提示", "Direction hints"),
    ("hints_on", "开", "On"),
    ("hints_off", "关", "Off"),
    ("hints_first2", "只在前两课", "First 2"),
    ("set_vsticks", "虚拟摇杆", "Stick pads"),
    ("vs_auto", "自动", "Auto"),
    ("vs_always", "常开", "Always"),
    ("vs_off", "关", "Off"),
    ("set_altline", "高度线", "Height line"),
    ("set_bigtag", "大字高度牌", "Big height tag"),
    ("recall", "叫回面板", "Bring panel back"),
    ("set_lang", "语言", "Language"),
    ("lang_auto", "自动", "Auto"),
    ("lang_zh", "中", "中"),
    ("lang_en", "EN", "EN"),
    ("reset", "重置进度", "Reset progress"),
    ("reset_q", "清掉所有成绩？", "Clear all your scores?"),
    ("reset_yes", "清掉", "Clear"),
    ("cancel", "取消", "Cancel"),
    ("about", "关于", "About"),
    ("about_1", "v0.1.3 · 科目名对照 CAAC 多旋翼视距内实践考试，场地按客厅缩放", "v0.1.3 · Lesson names follow the CAAC multirotor VLOS test, scaled to a living room"),
    ("about_2", "练手感用的，不是培训课时，不能替代报名考试", "For practice only. Not training hours, not a substitute for the real test"),
    ("quit", "退出应用", "Quit app"),
    # S12
    ("exit_title", "退出视距飞手？", "Quit Line-of-Sight Pilot?"),
    ("exit_body", "进度已保存", "Your progress is saved"),
    ("exit_yes", "退出", "Quit"),
    # S13
    ("blk_title", "现在用不了", "Can't fly right now"),
    ("blk_fullspace", "不在 Full Space", "Not in Full Space"),
    ("blk_head", "头显追踪没就绪", "Headset tracking isn't ready"),
    ("blk_retry", "再试一次", "Try again"),
    ("blk_quit", "退出", "Quit"),
]


def write(path, idx):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- generated by tools/strings_table.py: edit there -->", "<resources>"]
    for row in S:
        text = row[idx].replace("'", "\\'").replace('"', '\\"')
        lines.append(f'    <string name="{row[0]}">{escape(text)}</string>')
    lines.append("</resources>")
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")


def main():
    keys = [r[0] for r in S]
    dup = {k for k in keys if keys.count(k) > 1}
    assert not dup, dup
    write(os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"), 2)
    write(os.path.join(ROOT, "app", "src", "main", "res", "values-zh", "strings.xml"), 1)
    # width rule (copy.widthRule): English hints <= 2 x 40 characters
    for k, zh, en in S:
        if k.startswith("h_") and len(en) > 80:
            print("TOO LONG", k, len(en))
    print(f"{len(S)} strings written")


if __name__ == "__main__":
    main()

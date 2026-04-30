package com.jldaren.agent.ai.datascope.util;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 日期工具类
 * 提供当前时间、节日计算、农历转换等功能
 * dlw
 */
@Slf4j
public class DateTimeUtil {

    private static final DateTimeFormatter DATETIME_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss (EEEE)");
    
    private static final DateTimeFormatter DATE_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 获取当前格式化时间字符串
     */
    public static String getCurrentDateTimeStr() {
        return LocalDateTime.now().format(DATETIME_FORMATTER);
    }

    /**
     * 获取当前日期
     */
    public static LocalDate getCurrentDate() {
        return LocalDate.now();
    }

    /**
     * 获取当前年份
     */
    public static int getCurrentYear() {
        return LocalDate.now().getYear();
    }

    /**
     * 获取农历信息
     * @return 农历日期字符串，如 "丙午年(马)四月十五"
     */
    public static String getLunarInfo() {
        LocalDate today = LocalDate.now();
        try {
            Solar solar = Solar.fromYmd(today.getYear(), today.getMonthValue(), today.getDayOfMonth());
            Lunar lunar = solar.getLunar();
            return lunar.getYearInGanZhi() + "年(" + lunar.getYearShengXiao() + ")"
                   + lunar.getMonthInChinese() + "月" + lunar.getDayInChinese();
        } catch (Exception e) {
            log.warn("获取农历信息失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 生成节日提醒信息
     * 根据当前日期，列出即将到来和已过的节日（未来30天内）
     * @return 节日信息字符串
     */
    public static String generateHolidayInfo() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        int month = today.getMonthValue();
        int dayOfMonth = today.getDayOfMonth();

        StringBuilder info = new StringBuilder();
        info.append("\n\n【农历信息】系统时间: ").append(today).append(" 年份:").append(year).append("\n");

        // 计算节日
        List<String> upcomingHolidays = new ArrayList<>();
        List<String> passedHolidays = new ArrayList<>();

        java.time.temporal.ChronoUnit DAYS = java.time.temporal.ChronoUnit.DAYS;

        try {
            // 使用 lunar 计算农历日期
            Solar solar = Solar.fromYmd(year, month, dayOfMonth);
            Lunar lunar = solar.getLunar();

            // 添加农历信息
            info.append("农历: ").append(lunar.getYearInGanZhi()).append("年(")
               .append(lunar.getYearShengXiao()).append(")")
               .append(lunar.getMonthInChinese()).append("月")
               .append(lunar.getDayInChinese()).append("\n");

            // ========== 农历节日 ==========
            addLunarHoliday(lunar, 1, 1, "春节", "阖家团圆、拜年走亲戚", upcomingHolidays, passedHolidays, today, DAYS);
            addLunarHoliday(lunar, 1, 15, "元宵节", "赏花灯、吃元宵", upcomingHolidays, passedHolidays, today, DAYS);
            addLunarHoliday(lunar, 5, 5, "端午节", "吃粽子、赛龙舟", upcomingHolidays, passedHolidays, today, DAYS);
            addLunarHoliday(lunar, 7, 7, "七夕节", "中国传统情人节", upcomingHolidays, passedHolidays, today, DAYS);
            addLunarHoliday(lunar, 8, 15, "中秋节", "赏月、吃月饼", upcomingHolidays, passedHolidays, today, DAYS);
            addLunarHoliday(lunar, 9, 9, "重阳节", "登高赏秋、敬老", upcomingHolidays, passedHolidays, today, DAYS);
            addLunarHoliday(lunar, 12, 8, "腊八节", "喝腊八粥", upcomingHolidays, passedHolidays, today, DAYS);

        } catch (Exception e) {
            log.warn("计算农历信息失败: {}", e.getMessage());
        }

        // ========== 公历节日（独立计算，不受农历异常影响）==========
        addSolarHoliday(today, year, 1, 1, "元旦", "新年伊始", upcomingHolidays, passedHolidays, DAYS);
        addSolarHoliday(today, year, 4, 5, "清明节", "扫墓祭祖、踏青", upcomingHolidays, passedHolidays, DAYS);
        addSolarHoliday(today, year, 3, 8, "妇女节", "祝愿女性同胞节日快乐", upcomingHolidays, passedHolidays, DAYS);
        addSolarHoliday(today, year, 5, 1, "劳动节", "劳动最光荣！", upcomingHolidays, passedHolidays, DAYS);
        addSolarHoliday(today, year, 6, 1, "儿童节", "祝愿小朋友节日快乐", upcomingHolidays, passedHolidays, DAYS);
        addSolarHoliday(today, year, 10, 1, "国庆节", "国庆快乐！", upcomingHolidays, passedHolidays, DAYS);
        addSolarHoliday(today, year, 12, 25, "圣诞节", "Merry Christmas!", upcomingHolidays, passedHolidays, DAYS);

        // 输出即将到来的节日（14天内）
        if (!upcomingHolidays.isEmpty()) {
            info.append("\n【即将到来的节日】（未来14天内）：\n");
            for (String h : upcomingHolidays) {
                info.append("- ").append(h).append("\n");
            }
        }

        // 输出已过的节日（同年内，距离不超过30天）
        if (!passedHolidays.isEmpty()) {
            info.append("\n【近期已过的节日】：\n");
            for (String h : passedHolidays) {
                info.append("- ").append(h).append("\n");
            }
        }

        return info.length() > 0 ? info.toString() : "";
    }

    /**
     * 添加农历节日
     */
    private static void addLunarHoliday(Lunar currentLunar, int targetMonth, int targetDay,
            String name, String suggestion,
            List<String> upcoming, List<String> passed,
            LocalDate today, java.time.temporal.ChronoUnit DAYS) {
        try {
            // 计算目标节日对应的公历日期
            Lunar targetLunar = Lunar.fromYmd(currentLunar.getYear(), targetMonth, targetDay);
            Solar targetSolar = targetLunar.getSolar();
            LocalDate targetDate = LocalDate.of(targetSolar.getYear(), targetSolar.getMonth(), targetSolar.getDay());

            // 如果今年的已过，尝试明年
            if (targetDate.isBefore(today)) {
                Lunar nextYearLunar = Lunar.fromYmd(currentLunar.getYear() + 1, targetMonth, targetDay);
                Solar nextYearSolar = nextYearLunar.getSolar();
                targetDate = LocalDate.of(nextYearSolar.getYear(), nextYearSolar.getMonth(), nextYearSolar.getDay());
            }

            long daysUntil = DAYS.between(today, targetDate);

            if (daysUntil == 0) {
                upcoming.add("【今天】" + name + "！祝你" + name + "快乐！🎉 " + suggestion);
            } else if (daysUntil > 0 && daysUntil <= 14) {
                upcoming.add("【" + daysUntil + "天后】" + name + "(" + targetDate + ") - " + suggestion);
            } else if (daysUntil < 0) {
                long daysSince = -daysUntil;
                if (daysSince <= 30) {
                    passed.add("【" + daysSince + "天前】" + name + "(" + targetDate + ")");
                }
            }
        } catch (Exception e) {
            log.debug("计算农历节日{}失败: {}", name, e.getMessage());
        }
    }

    /**
     * 添加公历节日
     */
    private static void addSolarHoliday(LocalDate today, int year, int month, int day,
            String name, String suggestion,
            List<String> upcoming, List<String> passed,
            java.time.temporal.ChronoUnit DAYS) {
        try {
            LocalDate holidayDate = LocalDate.of(year, month, day);
            long daysUntil = DAYS.between(today, holidayDate);

            if (daysUntil == 0) {
                upcoming.add("【今天】" + name + "！祝你节日快乐！🎉 " + suggestion);
            } else if (daysUntil > 0 && daysUntil <= 14) {
                upcoming.add("【" + daysUntil + "天后】" + name + "(" + holidayDate + ") - " + suggestion);
            } else if (daysUntil < 0) {
                long daysSince = -daysUntil;
                if (daysSince <= 30) {
                    passed.add("【" + daysSince + "天前】" + name + "(" + holidayDate + ")");
                }
            }
        } catch (Exception e) {
            log.debug("计算公历节日{}失败: {}", name, e.getMessage());
        }
    }
    
    /**
     * 生成生日提醒信息
     * @param birthdays 用户生日列表，格式："2026-05-01" 或 "05-01"（只月日）
     * @return 生日提醒信息
     */
    public static String generateBirthdayReminder(List<String> birthdays) {
        if (birthdays == null || birthdays.isEmpty()) {
            return "";
        }
        
        LocalDate today = LocalDate.now();
        int currentYear = today.getYear();
        int currentMonth = today.getMonthValue();
        int currentDay = today.getDayOfMonth();
        
        StringBuilder reminder = new StringBuilder();
        java.time.temporal.ChronoUnit DAYS = java.time.temporal.ChronoUnit.DAYS;
        
        for (String birthday : birthdays) {
            try {
                LocalDate birthdayDate;
                if (birthday.contains("-") && birthday.split("-").length == 3) {
                    // 完整日期格式：2026-05-01
                    birthdayDate = LocalDate.parse(birthday);
                } else {
                    // 只月日格式：05-01，拼接今年年份
                    birthdayDate = LocalDate.parse(currentYear + "-" + birthday);
                }
                
                // 如果今年已过，尝试明年
                if (birthdayDate.isBefore(today) || birthdayDate.isEqual(today)) {
                    birthdayDate = birthdayDate.plusYears(1);
                }
                
                long daysUntil = DAYS.between(today, birthdayDate);
                
                // 只提醒7天内的生日
                if (daysUntil > 0 && daysUntil <= 7) {
                    if (reminder.length() == 0) {
                        reminder.append("\n【生日提醒】（7天内）：\n");
                    }
                    if (daysUntil == 1) {
                        reminder.append("- 🎂明天是【").append(birthday).append("】的生日！别忘了送上祝福哦~\n");
                    } else {
                        reminder.append("- 🎂【").append(birthday).append("】还有").append(daysUntil).append("天就是生日啦~\n");
                    }
                }
            } catch (Exception e) {
                log.debug("解析生日日期失败: {}", birthday);
            }
        }
        
        return reminder.toString();
    }
}


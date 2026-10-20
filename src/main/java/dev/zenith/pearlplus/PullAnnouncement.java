package dev.zenith.pearlplus;

public final class PullAnnouncement {
    private PullAnnouncement() {}

    public static String format(boolean publicRequest, String template, String player, String pearl, String time) {
        // A private request must never expose the sender or their pearl in public chat.
        if (!publicRequest) return "Member of Divinity have been pulled";
        if (template == null || template.isBlank() || !template.contains("{player}")) template = "{player} has been pulled !";
        return template.replace("{player}", player).replace("{pearl}", pearl).replace("{time}", time);
    }
}

package postop.hcii.hebo;

/**
 * Config file with constants
 */

 final class Config {
    private Config() {}

    // Logistical configurations
    public static final String DIALOGFLOW_API_KEY = "9db2ebf5fd0c406980227d238be8fcca";
    public static final String VISUAL_IMAGES_URL = "https://raw.githubusercontent.com/hebochatbot/hcii-postop/master/Hebo/app/src/main/assets/";
    public static final String NOTIFICATION_CHANNEL = "Notifications";
    public static final String STILL_BLEEDING = "still-bleeding";
    public static final String DEFAULT_SITE = "neck"; // default for when surgery site isn't selected
    public static final int TIMER_START_VALUE = 1800000; // 30 minutes
    public static final int ERR_THRESHOLD = 1; // if intent is repeated ERR_THRESHOLD times, we should recommend calling

    // Bitvectors to indicate profile selections
    public static final int PROFILE_SITE = 0x1;
    public static final int PROFILE_DATE = 0x2;
    public static final int PROFILE_TIME = 0x4;
    public static final int PROFILE_CLINIC = 0x8;
    public static final int PROFILE_COMPLETE = 0xF;

    // Types of messages that can be displayed in the Message Adapter
    public static final int MESSAGE_SENT = 1;
    public static final int MESSAGE_HEBO_TEXT = 2;
    public static final int MESSAGE_HEBO_VISUAL = 3;
    public static final int MESSAGE_HEBO_TIMER = 4;

}

package il.co.arlozorovauto;

/** Shared local-broadcast action fired by the background services/receivers
 *  whenever a scheduled item's state changes (registered, waitlisted,
 *  failed, cancelled...), so MainActivity can refresh its lists right away
 *  instead of waiting for the next 5s poll - the same immediacy as
 *  cancelling a class from inside the app. */
public class AppEvents {
    public static final String ACTION_DATA_CHANGED = "il.co.arlozorovauto.DATA_CHANGED";
    private AppEvents() {}
}

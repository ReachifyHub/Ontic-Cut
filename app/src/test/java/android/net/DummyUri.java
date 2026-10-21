package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

public class DummyUri extends Uri {
    public static final DummyUri INSTANCE = new DummyUri();

    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public String getScheme() { return "file"; }
    @Override public String getSchemeSpecificPart() { return ""; }
    @Override public String getEncodedSchemeSpecificPart() { return ""; }
    @Override public String getAuthority() { return ""; }
    @Override public String getEncodedAuthority() { return ""; }
    @Override public String getUserInfo() { return null; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getHost() { return null; }
    @Override public int getPort() { return -1; }
    @Override public String getPath() { return "/dummy"; }
    @Override public String getEncodedPath() { return "/dummy"; }
    @Override public String getQuery() { return null; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public List<String> getPathSegments() { return Collections.emptyList(); }
    @Override public String getLastPathSegment() { return "dummy"; }
    @Override public Builder buildUpon() { return null; }
    @Override public String toString() { return "file:///dummy"; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
}

package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

public class DummyUri extends Uri {
    public static final DummyUri INSTANCE = new DummyUri();

    private final String customPath;

    public DummyUri() {
        this.customPath = "/dummy";
    }

    public DummyUri(String customPath) {
        this.customPath = customPath != null ? customPath : "/dummy";
    }

    public static DummyUri fromPath(String path) {
        return new DummyUri(path);
    }

    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public String getScheme() { return "file"; }
    @Override public String getSchemeSpecificPart() { return customPath; }
    @Override public String getEncodedSchemeSpecificPart() { return customPath; }
    @Override public String getAuthority() { return ""; }
    @Override public String getEncodedAuthority() { return ""; }
    @Override public String getUserInfo() { return null; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getHost() { return null; }
    @Override public int getPort() { return -1; }
    @Override public String getPath() { return customPath; }
    @Override public String getEncodedPath() { return customPath; }
    @Override public String getQuery() { return null; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public List<String> getPathSegments() { return Collections.emptyList(); }
    @Override public String getLastPathSegment() {
        if (customPath != null && customPath.contains("/")) {
            return customPath.substring(customPath.lastIndexOf('/') + 1);
        }
        return "dummy";
    }
    @Override public Builder buildUpon() { return null; }
    @Override public String toString() { return customPath != null ? customPath : "file:///dummy"; }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof Uri) {
            return this.toString().equals(o.toString());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }
}

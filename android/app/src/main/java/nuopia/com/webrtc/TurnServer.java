package nuopia.com.webrtc;

import retrofit2.Call;
import retrofit2.http.GET;

public interface TurnServer {
    @GET("ice?ident=vivekchanddru&secret=ad6ce53a-e6b5-11e6-9685-937ad99985b9&domain=www.vivekc.xyz&application=default&room=testing&secure=1")
    Call<TurnServerPojo> getIceCandidates();
}

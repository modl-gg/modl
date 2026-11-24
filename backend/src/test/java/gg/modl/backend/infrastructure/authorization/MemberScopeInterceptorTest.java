package gg.modl.backend.infrastructure.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gg.modl.backend.infrastructure.exception.BaseApplicationException;
import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.infrastructure.rest.RequestAttribute;
import gg.modl.backend.instance.MinecraftCredential;
import gg.modl.backend.serverkey.ServerKeyErrors;
import gg.modl.backend.serverkey.ServerKeyScope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class MemberScopeInterceptorTest {
    private final MemberScopeInterceptor interceptor = new MemberScopeInterceptor();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void aMemberKeyReachesOnlyMemberAccessibleHandlers() throws NoSuchMethodException {
        MockHttpServletRequest request = withCredential(new MinecraftCredential(ServerKeyScope.BEHIND_PROXY, "k1", "lobby"));

        assertThat(interceptor.preHandle(request, response, handler("allowed"))).isTrue();
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler("other")))
            .isInstanceOf(ForbiddenException.class)
            .extracting(failure -> ((BaseApplicationException) failure).getMachineCode()).isEqualTo(ServerKeyErrors.SCOPE);
        assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void fullCredentialsAndOtherRequestsPass() throws NoSuchMethodException {
        assertThat(interceptor.preHandle(withCredential(MinecraftCredential.instanceKey()), response, handler("other"))).isTrue();
        assertThat(interceptor.preHandle(new MockHttpServletRequest(), response, handler("other"))).isTrue();
    }

    private static MockHttpServletRequest withCredential(MinecraftCredential credential) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttribute.MINECRAFT_CREDENTIAL, credential);
        return request;
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }

    public static final class Handlers {
        @MemberAccessible
        public void allowed() {
        }

        public void other() {
        }
    }
}

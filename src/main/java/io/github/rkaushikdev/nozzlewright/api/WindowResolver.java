package io.github.rkaushikdev.nozzlewright.api;

import io.github.rkaushikdev.nozzlewright.util.Times;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Hands a {@link Window} to any route that asks for one, read from {@code since} and {@code until}. */
public final class WindowResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Window.class;
    }

    @Override
    public Window resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binders) {
        return Window.of(
                QueryValues.instant("since", request.getParameter("since")),
                QueryValues.instant("until", request.getParameter("until")),
                Times.now());
    }
}

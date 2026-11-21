package io.github.rkaushikdev.nozzlewright.api;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Hands a {@link PageParams} to any route that asks for one, read from the {@code limit} (one to
 * {@value PageParams#MAX_PAGE}, default {@value PageParams#DEFAULT_PAGE}) and {@code offset} (nought
 * or more) query parameters.
 */
public final class PagingResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == PageParams.class;
    }

    @Override
    public PageParams resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binders) {
        int limit = QueryValues.integer(
                "limit", request.getParameter("limit"), PageParams.DEFAULT_PAGE, 1, PageParams.MAX_PAGE);
        int offset = QueryValues.integer("offset", request.getParameter("offset"), 0, 0);
        return new PageParams(limit, offset);
    }
}

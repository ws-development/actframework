package act.validation;

import act.app.AppContext;
import act.controller.ActionMethodParamAnnotationHandlerPlugin;
import org.osgl.util.C;
import org.osgl.util.E;

import javax.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public class SizeHandler extends ActionMethodParamAnnotationHandlerPlugin {
    @Override
    public Set<Class<? extends Annotation>> listenTo() {
        Set<Class<? extends Annotation>> set = C.newSet();
        set.add(Size.class);
        return set;
    }

    @Override
    public void handle(String paramName, Object paramVal, Annotation annotation, AppContext context) {
        if (null == paramVal) {
            return;
        }
        int size = sizeOf(paramVal);
        Size theAnno = (Size) annotation;
        if (size < theAnno.min() || size > theAnno.max()) {
            context.addViolation(new ActionMethodParamConstraintViolation<Object>(theAnno.message(), theAnno, context));
        }
    }

    private int sizeOf(Object val) {
        if (val instanceof CharSequence) {
            return ((CharSequence)val).length();
        } else if (val instanceof Collection) {
            return ((Collection) val).size();
        } else if (val instanceof Map) {
            return ((Map) val).size();
        } else if (val.getClass().isArray()) {
            return Array.getLength(val);
        } else {
            throw E.unexpected("Unexpected para val type: %s", val.getClass());
        }
    }

}
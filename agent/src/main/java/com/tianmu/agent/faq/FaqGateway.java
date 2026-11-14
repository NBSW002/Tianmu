package com.tianmu.agent.faq;

import com.tianmu.agent.preparation.FaqPort;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.control.CallControl;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Optional;

/** Developer-maintained FAQ entries only. */
@Component
@Primary
public final class FaqGateway implements FaqPort {
    private final ClasspathFaqPort bundled;
    public FaqGateway(ObjectProvider<ClasspathFaqPort> bundled){this.bundled=bundled.getIfAvailable();}
    public Optional<FaqAnswer> find(Input input,CallControl control){
        control.checkpoint();
        return bundled==null?Optional.empty():bundled.find(input,control);
    }
}

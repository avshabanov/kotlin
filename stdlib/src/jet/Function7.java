/*
 * @author alex.tkachman
 */
package jet;

public abstract class Function7<D1, D2, D3, D4, D5, D6, D7, R> extends DefaultJetObject {
    protected Function7(TypeInfo<?> typeInfo) {
        super(typeInfo);
    }

    public abstract R invoke(D1 d1, D2 d2, D3 d3, D4 d4, D5 d5, D6 d6, D7 d7);

    @Override
    public String toString() {
      return "{(d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6, d7: D7) : R)}";
    }
}


/*
 * @author alex.tkachman
 */
package jet;

public abstract class ExtensionFunction12<E, D1, D2, D3, D4, D5, D6, D7, D8, D9, D10, D11, D12, R> extends DefaultJetObject {
    protected ExtensionFunction12(TypeInfo<?> typeInfo) {
        super(typeInfo);
    }

    public abstract R invoke(E receiver, D1 d1, D2 d2, D3 d3, D4 d4, D5 d5, D6 d6, D7 d7, D8 d8, D9 d9, D10 d10, D11 d11, D12 d12);

    @Override
    public String toString() {
      return "{E.(d1: D1, d2: D2, d3: D3, d4: D4, d5: D5, d6: D6, d7: D7, d8: D8, d9: D9, d10: D10, d11: D11, d12: D12) : R)}";
    }
}


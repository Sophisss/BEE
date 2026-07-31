import {useState, useEffect} from 'react';
import {useEnvStore} from '../../../envStore';

const PhysicalAttributes = ({elementId, type, initialAttributes}: {
    elementId: string;
    type: 'place' | 'edge';
    initialAttributes: Record<string, any>;
}) => {
    const [attributes, setAttributes] = useState<Record<string, any>>(initialAttributes);
    const [tempKeys, setTempKeys] = useState<Record<string, string>>({});
    const updatePlace = useEnvStore((state) => state.updatePlace);
    const updateEdge = useEnvStore((state) => state.updateEdge);
    const isEditable = useEnvStore((state) => state.isEditable);

    useEffect(() => {
        setAttributes(initialAttributes);
        setTempKeys(Object.fromEntries(Object.keys(initialAttributes).map((k) => [k, k])));
    }, [initialAttributes]);

    const updateStore = (updated: Record<string, any>) => {
        setAttributes(updated);
        type === 'edge'
            ? updateEdge?.(elementId, {attributes: updated})
            : updatePlace?.(elementId, {attributes: updated});
    };

    const isInvalidKey = (newKey: string, oldKey: string): boolean => {
        return (
            newKey.trim() === '' ||
            newKey === oldKey ||
            Object.prototype.hasOwnProperty.call(attributes, newKey)
        );
    };

    const applyKeyChange = (oldKey: string, newKey: string) => {
        const updated = {...attributes};
        const value = updated[oldKey];
        delete updated[oldKey];
        updated[newKey] = value;
        updateStore(updated);

        const newTempKeys = {...tempKeys};
        delete newTempKeys[oldKey];
        newTempKeys[newKey] = newKey;
        setTempKeys(newTempKeys);
    };

    const handleKeyBlur = (oldKey: string, newKey: string) => {
        if (isInvalidKey(newKey, oldKey)) {
            setTempKeys((prev) => ({...prev, [oldKey]: oldKey}));
            return;
        }

        applyKeyChange(oldKey, newKey);
    };

    const handleTempKeyChange = (oldKey: string, newTemp: string) => {
        setTempKeys((prev) => ({...prev, [oldKey]: newTemp}));
    };

    const handleValueChange = (key: string, value: string) => {
        // if (value.trim() === '') return;
        const updated = {...attributes, [key]: value};
        updateStore(updated);
    };

    const handleRemoveAttribute = (key: string) => {
        const updated = {...attributes};
        delete updated[key];
        updateStore(updated);
    };

    return (
        <div className="mt-2 ms-3">
            {Object.entries(attributes).map(([key, value], index) => {
                const isTagsAttribute = type === 'place' && key === 'tags';

                return (
                    <div key={index} className="mb-3 px-1">
                        <div className="d-flex align-items-center gap-2 w-100">
                            <input
                                className="form-control form-control-md border-0 bg-transparent text-white p-0 custom-placeholder no-focus-outline"
                                value={tempKeys[key] ?? key}
                                onChange={(e) => handleTempKeyChange(key, e.target.value)}
                                onBlur={(e) => handleKeyBlur(key, e.target.value)}
                                placeholder="Attribute Key"
                                disabled={!isEditable || isTagsAttribute}
                                spellCheck={false}
                            />
                            <input
                                className="form-control form-control-md border-0 bg-transparent text-white p-0 custom-placeholder no-focus-outline"
                                value={value}
                                onChange={(e) => handleValueChange(key, e.target.value)}
                                onBlur={(e) => handleValueChange(key, e.target.value)}
                                placeholder={isEditable ? "Attribute Value" : "null"}
                                disabled={!isEditable}
                                spellCheck={false}
                            />
                            {!isTagsAttribute ? (
                                <button
                                    className="btn btn-sm btn-outline-danger p-1"
                                    style={{width: '24px', height: '24px', lineHeight: '12px'}}
                                    onClick={() => handleRemoveAttribute(key)}
                                    title="Remove"
                                    hidden={!isEditable}
                                >
                                    ✕
                                </button>
                            ) : (
                                <div style={{width: '24px'}}/>
                            )}
                        </div>
                        {isTagsAttribute && (
                            <div
                                className="mt-1 p-1 rounded"
                                style={{
                                    backgroundColor: 'rgba(56, 189, 248, 0.1)',
                                    borderLeft: '3px solid #38BDF8',
                                    fontSize: '0.72rem',
                                    color: '#38BDF8'
                                }}
                            >
                                💡 <strong>Tags:</strong> Separate values with commas (e.g. <em>Rinascimento,
                                Pittura</em>). They become preferences/choices in the app.
                            </div>
                        )}
                    </div>
                );
            })}
        </div>
    );
};

export default PhysicalAttributes;
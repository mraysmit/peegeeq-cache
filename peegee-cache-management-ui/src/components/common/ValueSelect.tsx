import { Select, type SelectProps } from 'antd';

export interface ValueOption<V extends string> {
  readonly value: V;
  readonly label: string;
}

/**
 * antd Select for reviewed server enum values. Each option and the selected label carry a
 * `data-value` attribute with the server value, so the browser suite can address options by the
 * contract value (`AntSelect.choose(page, combobox, "EXPIRED_BACKLOG")`) while operators see
 * the human label. Composes antd `Select` only (design §3.1 mandate).
 */
export function ValueSelect<V extends string>({ options, value, onChange, ...rest }: Omit<SelectProps<V>, 'options' | 'value' | 'onChange' | 'optionRender' | 'labelRender'> & {
  readonly options: ReadonlyArray<ValueOption<V>>;
  readonly value: V | undefined;
  readonly onChange: (value: V) => void;
}) {
  return (
    <Select<V>
      {...rest}
      labelRender={(item) => <span data-value={String(item.value)}>{item.label}</span>}
      onChange={(next) => onChange(next)}
      optionRender={(option) => <span data-value={String(option.value)}>{option.label}</span>}
      options={options.map((option) => ({ value: option.value, label: option.label }))}
      value={value}
    />
  );
}

export default ValueSelect;

import React, { useState } from 'react';
import { Box, Text, useInput } from 'ink';

export interface PickerItem<T extends string> {
  value: T;
  label: string;
  hint?: string;
}

interface ListPickerProps<T extends string> {
  title: string;
  items: PickerItem<T>[];
  selected: T;
  onSelect: (value: T) => void;
  onBack: () => void;
}

/**
 * Single-select keyboard picker. ↑↓ + ↵ select, esc to go back.
 * Used by Mode / Target / Platform screens — same UX, different items.
 */
export function ListPicker<T extends string>({ title, items, selected, onSelect, onBack }: ListPickerProps<T>) {
  const initialIdx = Math.max(0, items.findIndex((i) => i.value === selected));
  const [cursor, setCursor] = useState(initialIdx);

  useInput((_, key) => {
    if (key.escape) onBack();
    else if (key.upArrow) setCursor((c) => Math.max(0, c - 1));
    else if (key.downArrow) setCursor((c) => Math.min(items.length - 1, c + 1));
    else if (key.return) onSelect(items[cursor]!.value);
  });

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <Text bold color="cyan">{title}</Text>
      <Text color="gray" dimColor>↑↓ move  ·  ↵ select  ·  esc back</Text>
      <Box marginTop={1} flexDirection="column">
        {items.map((item, i) => {
          const isCursor = i === cursor;
          const isSelected = item.value === selected;
          return (
            <Box key={item.value}>
              <Text color={isCursor ? 'cyan' : 'gray'}>{isCursor ? '› ' : '  '}</Text>
              <Text color={isSelected ? 'cyan' : 'white'}>{isSelected ? '●' : '○'}</Text>
              <Text>  </Text>
              <Text bold={isCursor || isSelected}>{item.label}</Text>
              {item.hint ? <Text color="gray" dimColor>  {item.hint}</Text> : null}
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}

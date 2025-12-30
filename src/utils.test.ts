import {describe, it, expect, jest} from '@jest/globals';
import utils from './utils.js';

describe('utils', () => {
    describe('chunkedList', () => {
        it('should split an array into chunks of the specified size', () => {
            const input = [1, 2, 3, 4, 5];
            const result = utils.chunkedList(input, 2);
            expect(result).toEqual([[1, 2], [3, 4], [5]]);
        });

        it('should return an empty array if input is empty', () => {
            const result = utils.chunkedList([], 2);
            expect(result).toEqual([]);
        });

        it('should handle chunk size larger than array length', () => {
            const input = [1, 2, 3];
            const result = utils.chunkedList(input, 5);
            expect(result).toEqual([[1, 2, 3]]);
        });
    });

    describe('shuffle', () => {
        it('should return an array of the same length', () => {
            const input = [1, 2, 3, 4, 5];
            const originalLength = input.length;
            const result = utils.shuffle([...input]);
            expect(result.length).toBe(originalLength);
        });

        it('should contain the same elements after shuffling', () => {
            const input = [1, 2, 3, 4, 5];
            const result = utils.shuffle([...input]);
            expect(result.sort()).toEqual(input.sort());
        });

        it('should return a predictable result when Math.random is mocked', () => {
            const input = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
            const mockValues = [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9];
            let mockIndex = 0;
            const spy = jest.spyOn(Math, 'random').mockImplementation(() => mockValues[mockIndex++]);

            const result = utils.shuffle([...input]);

            expect(result).toEqual([1, 9, 5, 7, 6, 4, 8, 3, 10, 2]);
            spy.mockRestore();
        });
    });
});
